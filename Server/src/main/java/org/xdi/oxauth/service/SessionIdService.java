/*
 * oxAuth is available under the MIT License (2008). See http://opensource.org/licenses/MIT for full text.
 *
 * Copyright (c) 2014, Gluu
 */

package org.xdi.oxauth.service;

import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ResultCode;
import org.apache.commons.lang.StringUtils;
import org.gluu.site.ldap.persistence.exception.EmptyEntryPersistenceException;
import org.gluu.site.ldap.persistence.exception.EntryPersistenceException;
import org.slf4j.Logger;
import org.xdi.oxauth.audit.ApplicationAuditLogger;
import org.xdi.oxauth.model.audit.Action;
import org.xdi.oxauth.model.audit.OAuth2AuditLog;
import org.xdi.oxauth.model.common.Prompt;
import org.xdi.oxauth.model.common.SessionId;
import org.xdi.oxauth.model.common.SessionIdState;
import org.xdi.oxauth.model.config.StaticConfiguration;
import org.xdi.oxauth.model.config.WebKeysConfiguration;
import org.xdi.oxauth.model.configuration.AppConfiguration;
import org.xdi.oxauth.model.crypto.signature.SignatureAlgorithm;
import org.xdi.oxauth.model.exception.AcrChangedException;
import org.xdi.oxauth.model.jwt.Jwt;
import org.xdi.oxauth.model.jwt.JwtClaimName;
import org.xdi.oxauth.model.jwt.JwtSubClaimObject;
import org.xdi.oxauth.model.token.JwtSigner;
import org.xdi.oxauth.model.util.Util;
import org.xdi.oxauth.service.external.ExternalAuthenticationService;
import org.xdi.oxauth.util.ServerUtil;
import org.xdi.service.CacheService;
import org.xdi.util.StringHelper;

import javax.ejb.Stateless;
import javax.faces.context.ExternalContext;
import javax.faces.context.FacesContext;
import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

/**
 * @author Yuriy Zabrovarnyy
 * @author Yuriy Movchan
 * @author Javier Rojas Blum
 * @version August 9, 2017
 */
@Stateless
@Named
public class SessionIdService {

    public static final String SESSION_STATE_COOKIE_NAME = "session_state";
    public static final String SESSION_ID_COOKIE_NAME = "session_id";
    public static final String UMA_SESSION_ID_COOKIE_NAME = "uma_session_id";
    public static final String SESSION_CUSTOM_STATE = "session_custom_state";

    @Inject
    private Logger log;

    @Inject
    private ExternalAuthenticationService externalAuthenticationService;

    @Inject
    private ApplicationAuditLogger applicationAuditLogger;

    @Inject
    private AppConfiguration appConfiguration;

    @Inject
    private StaticConfiguration staticConfiguration;

    @Inject
    private WebKeysConfiguration webKeysConfiguration;

    @Inject
    private FacesContext facesContext;

    @Inject
    private ExternalContext externalContext;

    @Inject
    private CacheService cacheService;

    public String getAcr(SessionId session) {
        if (session == null || session.getSessionAttributes() == null) {
            return null;
        }

        String acr = session.getSessionAttributes().get(JwtClaimName.AUTHENTICATION_CONTEXT_CLASS_REFERENCE);
        if (StringUtils.isBlank(acr)) {
            acr = session.getSessionAttributes().get("acr_values");
        }
        return acr;
    }

    // #34 - update session attributes with each request
    // 1) redirect_uri change -> update session
    // 2) acr change -> throw acr change exception
    // 3) client_id change -> do nothing
    // https://github.com/GluuFederation/oxAuth/issues/34
    public SessionId assertAuthenticatedSessionCorrespondsToNewRequest(SessionId session, String acrValuesStr) throws AcrChangedException {
        if (session != null && !session.getSessionAttributes().isEmpty() && session.getState() == SessionIdState.AUTHENTICATED) {

            final Map<String, String> sessionAttributes = session.getSessionAttributes();

            String sessionAcr = getAcr(session);

            if (StringUtils.isBlank(sessionAcr)) {
                log.error("Failed to fetch acr from session, attributes: " + sessionAttributes);
                return session;
            }

            boolean isAcrChanged = acrValuesStr != null && !acrValuesStr.equals(sessionAcr);
            if (isAcrChanged) {
                Map<String, Integer> acrToLevel = externalAuthenticationService.acrToLevelMapping();
                Integer sessionAcrLevel = acrToLevel.get(sessionAcr);
                Integer currentAcrLevel = acrToLevel.get(acrValuesStr);

                log.info("Acr is changed. Session acr: " + sessionAcr + "(level: " + sessionAcrLevel + "), " +
                        "current acr: " + acrValuesStr + "(level: " + currentAcrLevel + ")");
                if ((sessionAcrLevel != null) && (sessionAcrLevel < currentAcrLevel)) {
                    throw new AcrChangedException();
                } else { // https://github.com/GluuFederation/oxAuth/issues/291
                    return session; // we don't want to reinit login because we have stronger acr (avoid overriding)
                }
            }

            reinitLogin(session, false);
        }
        return session;
    }

    public void reinitLogin(SessionId session, boolean force) {
        final Map<String, String> sessionAttributes = session.getSessionAttributes();
        final Map<String, String> currentSessionAttributes = getCurrentSessionAttributes(sessionAttributes);
        if (force || !currentSessionAttributes.equals(sessionAttributes)) {
            sessionAttributes.putAll(currentSessionAttributes);

            // Reinit login
            sessionAttributes.put("c", "1");

            for (Iterator<Entry<String, String>> it = currentSessionAttributes.entrySet().iterator(); it.hasNext(); ) {
                Entry<String, String> currentSessionAttributesEntry = it.next();
                String name = currentSessionAttributesEntry.getKey();
                if (name.startsWith("auth_step_passed_")) {
                    it.remove();
                }
            }

            session.setSessionAttributes(currentSessionAttributes);

            boolean updateResult = updateSessionId(session, true, true, true);
            if (!updateResult) {
                log.debug("Failed to update session entry: '{}'", session.getId());
            }
        }
    }

    public void resetToStep(SessionId session, int resetToStep) {
        final Map<String, String> sessionAttributes = session.getSessionAttributes();

        int currentStep = 1;
        if (sessionAttributes.containsKey("auth_step")) {
            currentStep = StringHelper.toInteger(sessionAttributes.get("auth_step"), currentStep);
        }

        for (int i = resetToStep; i <= currentStep; i++) {
            String key = String.format("auth_step_passed_%d", i);
            sessionAttributes.remove(key);
        }

        sessionAttributes.put("auth_step", String.valueOf(resetToStep));

        boolean updateResult = updateSessionId(session, true, true, true);
        if (!updateResult) {
            log.debug("Failed to update session entry: '{}'", session.getId());
        }
    }

    private Map<String, String> getCurrentSessionAttributes(Map<String, String> sessionAttributes) {
        // Update from request
        if (facesContext != null) {
            // Clone before replacing new attributes
            final Map<String, String> currentSessionAttributes = new HashMap<String, String>(sessionAttributes);

            Map<String, String> parameterMap = externalContext.getRequestParameterMap();
            Map<String, String> newRequestParameterMap = AuthenticationService.getAllowedParameters(parameterMap);
            for (Entry<String, String> newRequestParameterMapEntry : newRequestParameterMap.entrySet()) {
                String name = newRequestParameterMapEntry.getKey();
                if (!StringHelper.equalsIgnoreCase(name, "auth_step")) {
                    currentSessionAttributes.put(name, newRequestParameterMapEntry.getValue());
                }
            }

            return currentSessionAttributes;
        } else {
            return sessionAttributes;
        }
    }

    public String getSessionIdFromCookie(HttpServletRequest request) {
        return getSessionIdFromCookie(request, SESSION_ID_COOKIE_NAME);
    }

    public String getUmaSessionIdFromCookie(HttpServletRequest request) {
        return getSessionIdFromCookie(request, UMA_SESSION_ID_COOKIE_NAME);
    }

    public String getSessionIdFromCookie(HttpServletRequest request, String cookieName) {
        try {
            final Cookie[] cookies = request.getCookies();
            if (cookies != null) {
                for (Cookie cookie : cookies) {
                    if (cookie.getName().equals(cookieName) /*&& cookie.getSecure()*/) {
                        log.trace("Found session_id cookie: '{}'", cookie.getValue());
                        return cookie.getValue();
                    }
                }
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return "";
    }

    public String getSessionIdFromCookie() {
        try {
            if (facesContext == null) {
                return null;
            }
            final HttpServletRequest request = (HttpServletRequest) externalContext.getRequest();
            if (request != null) {
                return getSessionIdFromCookie(request);
            } else {
                log.error("Faces context returns null for http request object.");
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }

        return null;
    }

    public void createSessionIdCookie(String sessionId, String sessionState, HttpServletResponse httpResponse, boolean isUma) {
        String cookieName = isUma ? UMA_SESSION_ID_COOKIE_NAME : SESSION_ID_COOKIE_NAME;
        String header = cookieName + "=" + sessionId;
        header += "; Path=/";
        header += "; Secure";
        header += "; HttpOnly";

        Integer sessionStateLifetime = appConfiguration.getSessionIdLifetime();
        if (sessionStateLifetime != null) {
            DateFormat formatter = new SimpleDateFormat("E, dd MMM yyyy HH:mm:ss Z");
            Calendar expirationDate = Calendar.getInstance();
            expirationDate.add(Calendar.SECOND, sessionStateLifetime);
            header += "; Expires=" + formatter.format(expirationDate.getTime()) + ";";
        }

        httpResponse.addHeader("Set-Cookie", header);

        createSessionStateCookie(sessionState, httpResponse);
    }

    public void createSessionIdCookie(String sessionId, String sessionState, boolean isUma) {
        try {
            final Object response = externalContext.getResponse();
            if (response instanceof HttpServletResponse) {
                final HttpServletResponse httpResponse = (HttpServletResponse) response;

                createSessionIdCookie(sessionId, sessionState, httpResponse, isUma);
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    public void createSessionStateCookie(String sessionState, HttpServletResponse httpResponse) {
        // Create the special cookie header with secure flag but not HttpOnly because the session_state
        // needs to be read from the OP iframe using JavaScript
        String header = SESSION_STATE_COOKIE_NAME + "=" + sessionState;
        header += "; Path=/";
        header += "; Secure";

        Integer sessionStateLifetime = appConfiguration.getSessionIdLifetime();
        if (sessionStateLifetime != null) {
            DateFormat formatter = new SimpleDateFormat("E, dd MMM yyyy HH:mm:ss Z");
            Calendar expirationDate = Calendar.getInstance();
            expirationDate.add(Calendar.SECOND, sessionStateLifetime);
            header += "; Expires=" + formatter.format(expirationDate.getTime()) + ";";
        }

        httpResponse.addHeader("Set-Cookie", header);
    }

    public void removeSessionIdCookie(HttpServletResponse httpResponse) {
        final Cookie cookie = new Cookie(SESSION_ID_COOKIE_NAME, null); // Not necessary, but saves bandwidth.
        cookie.setPath("/");
        cookie.setMaxAge(0); // Don't set to -1 or it will become a session cookie!
        httpResponse.addCookie(cookie);
    }

    public void removeUmaSessionIdCookie(HttpServletResponse httpResponse) {
        final Cookie cookie = new Cookie(UMA_SESSION_ID_COOKIE_NAME, null); // Not necessary, but saves bandwidth.
        cookie.setPath("/");
        cookie.setMaxAge(0); // Don't set to -1 or it will become a session cookie!
        httpResponse.addCookie(cookie);
    }

    public SessionId getSessionId() {
        String sessionId = getSessionIdFromCookie();

        if (StringHelper.isNotEmpty(sessionId)) {
            return getSessionId(sessionId);
        }

        return null;
    }

    public Map<String, String> getSessionAttributes(SessionId sessionId) {
        if (sessionId != null) {
            return sessionId.getSessionAttributes();
        }

        return null;
    }

    public SessionId generateAuthenticatedSessionId(String userDn) {
        return generateAuthenticatedSessionId(userDn, "");
    }

    public SessionId generateAuthenticatedSessionId(String userDn, String prompt) {
        Map<String, String> sessionIdAttributes = new HashMap<String, String>();
        sessionIdAttributes.put("prompt", prompt);

        return generateSessionId(userDn, new Date(), SessionIdState.AUTHENTICATED, sessionIdAttributes, true);
    }

    public SessionId generateAuthenticatedSessionId(String userDn, Map<String, String> sessionIdAttributes) {
        return generateSessionId(userDn, new Date(), SessionIdState.AUTHENTICATED, sessionIdAttributes, true);
    }

    public SessionId generateUnauthenticatedSessionId(String userDn, Date authenticationDate, SessionIdState state, Map<String, String> sessionIdAttributes, boolean persist) {
        return generateSessionId(userDn, authenticationDate, state, sessionIdAttributes, persist);
    }

    private SessionId generateSessionId(String userDn, Date authenticationDate, SessionIdState state, Map<String, String> sessionIdAttributes, boolean persist) {
        final String sid = UUID.randomUUID().toString();
        final String sessionState = UUID.randomUUID().toString();
        final String dn = dn(sid);

        if (StringUtils.isBlank(dn)) {
            return null;
        }

        if (SessionIdState.AUTHENTICATED == state) {
            if (StringUtils.isBlank(userDn)) {
                return null;
            }
        }

        final SessionId sessionId = new SessionId();
        sessionId.setId(sid);
        sessionId.setDn(dn);
        sessionId.setUserDn(userDn);
        sessionId.setSessionState(sessionState);

        Boolean sessionAsJwt = appConfiguration.getSessionAsJwt();
        sessionId.setIsJwt(sessionAsJwt != null && sessionAsJwt);

        if (authenticationDate != null) {
            sessionId.setAuthenticationTime(authenticationDate);
        }

        if (state != null) {
            sessionId.setState(state);
        }

        sessionId.setSessionAttributes(sessionIdAttributes);
        sessionId.setLastUsedAt(new Date());

        if (sessionId.getIsJwt()) {
            sessionId.setJwt(generateJwt(sessionId, userDn).asString());
        }

        boolean persisted = false;
        if (persist) {
            persisted = persistSessionId(sessionId);
        }

        auditLogging(sessionId);

        log.trace("Generated new session, id = '{}', state = '{}', asJwt = '{}', persisted = '{}'", sessionId.getId(), sessionId.getState(), sessionId.getIsJwt(), persisted);
        return sessionId;
    }

    private Jwt generateJwt(SessionId sessionId, String audience) {
        try {
            JwtSigner jwtSigner = new JwtSigner(appConfiguration, webKeysConfiguration, SignatureAlgorithm.RS512, audience);
            Jwt jwt = jwtSigner.newJwt();

            // claims
            jwt.getClaims().setClaim("id", sessionId.getId());
            jwt.getClaims().setClaim("authentication_time", sessionId.getAuthenticationTime());
            jwt.getClaims().setClaim("user_dn", sessionId.getUserDn());
            jwt.getClaims().setClaim("state", sessionId.getState() != null ?
                    sessionId.getState().getValue() : "");

            jwt.getClaims().setClaim("session_attributes", JwtSubClaimObject.fromMap(sessionId.getSessionAttributes()));

            jwt.getClaims().setClaim("last_used_at", sessionId.getLastUsedAt());
            jwt.getClaims().setClaim("permission_granted", sessionId.getPermissionGranted());
            jwt.getClaims().setClaim("permission_granted_map", JwtSubClaimObject.fromBooleanMap(sessionId.getPermissionGrantedMap().getPermissionGranted()));
            jwt.getClaims().setClaim("involved_clients_map", JwtSubClaimObject.fromBooleanMap(sessionId.getInvolvedClients().getPermissionGranted()));

            // sign
            return jwtSigner.sign();
        } catch (Exception e) {
            log.error("Failed to sign session jwt! " + e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public SessionId setSessionIdStateAuthenticated(SessionId sessionId, String p_userDn) {
        sessionId.setUserDn(p_userDn);
        sessionId.setAuthenticationTime(new Date());
        sessionId.setState(SessionIdState.AUTHENTICATED);

        boolean persisted = updateSessionId(sessionId, true, true, true);

        auditLogging(sessionId);
        log.trace("Authenticated session, id = '{}', state = '{}', persisted = '{}'", sessionId.getId(), sessionId.getState(), persisted);
        return sessionId;
    }

    public boolean persistSessionId(final SessionId sessionId) {
        return persistSessionId(sessionId, false);
    }

    public boolean persistSessionId(final SessionId sessionId, boolean forcePersistence) {
        List<Prompt> prompts = getPromptsFromSessionId(sessionId);

        try {
            final int unusedLifetime = appConfiguration.getSessionIdUnusedLifetime();
            if ((unusedLifetime > 0 && isPersisted(prompts)) || forcePersistence) {
                sessionId.setLastUsedAt(new Date());

                sessionId.setPersisted(true);
                log.trace("sessionIdAttributes: " + sessionId.getPermissionGrantedMap());
                putInCache(sessionId);
                return true;
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }

        return false;
    }

    public boolean updateSessionId(final SessionId sessionId) {
        return updateSessionId(sessionId, true);
    }

    public boolean updateSessionId(final SessionId sessionId, boolean updateLastUsedAt) {
        return updateSessionId(sessionId, updateLastUsedAt, false, true);
    }

    public boolean updateSessionId(final SessionId sessionId, boolean updateLastUsedAt, boolean forceUpdate, boolean modified) {
        List<Prompt> prompts = getPromptsFromSessionId(sessionId);

        try {
            final int unusedLifetime = appConfiguration.getSessionIdUnusedLifetime();
            if ((unusedLifetime > 0 && isPersisted(prompts)) || forceUpdate) {
                boolean update = modified;

                if (updateLastUsedAt) {
                    Date lastUsedAt = new Date();
                    if (sessionId.getLastUsedAt() != null) {
                        long diff = lastUsedAt.getTime() - sessionId.getLastUsedAt().getTime();
                        if (diff > 500) { // update only if diff is more than 500ms
                            update = true;
                            sessionId.setLastUsedAt(lastUsedAt);
                        }
                    } else {
                        update = true;
                        sessionId.setLastUsedAt(lastUsedAt);
                    }
                }

                if (!sessionId.isPersisted()) {
                    update = true;
                    sessionId.setPersisted(true);
                }

                if (sessionId.getAuthenticationTime() != null) {
                    final long currentLifetimeInSeconds = (System.currentTimeMillis() - sessionId.getAuthenticationTime().getTime()) / 1000;
                    if (appConfiguration.getSessionIdLifetime() != null) {
                        if (currentLifetimeInSeconds > appConfiguration.getSessionIdLifetime()) {
                            log.debug("Session id expired: {}, remove it.", sessionId.getId());
                            remove(sessionId); // expired
                            update = false;
                        }
                    } else {
                        log.error("Session id lifetime configuration is null.");
                    }
                }

                if (update) {
                    try {
                        mergeWithRetry(sessionId, 3);
                    } catch (EmptyEntryPersistenceException ex) {
                        log.warn("Failed to update session entry '{}': '{}'", sessionId.getId(), ex.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return false;
        }

        return true;
    }

    private void putInCache(SessionId sessionId) {
        int expirationInSeconds = sessionId.getState() == SessionIdState.UNAUTHENTICATED ?
                appConfiguration.getSessionIdUnauthenticatedUnusedLifetime() :
                appConfiguration.getSessionIdLifetime();
        cacheService.put(Integer.toString(expirationInSeconds), sessionId.getId(), sessionId); // first parameter is expiration instead of region for memcached
    }

    private SessionId getFromCache(String sessionId) {
        return (SessionId) cacheService.get(null, sessionId);
    }

    private SessionId mergeWithRetry(final SessionId sessionId, int maxAttempts) {
        EntryPersistenceException lastException = null;
        for (int i = 1; i <= maxAttempts; i++) {
            try {
                putInCache(sessionId);
                return sessionId;
            } catch (EntryPersistenceException ex) {
                lastException = ex;
                if (ex.getCause() instanceof LDAPException) {
                    LDAPException parentEx = ((LDAPException) ex.getCause());
                    log.debug("LDAP exception resultCode: '{}'", parentEx.getResultCode().intValue());
                    if ((parentEx.getResultCode().intValue() == ResultCode.NO_SUCH_ATTRIBUTE_INT_VALUE) ||
                            (parentEx.getResultCode().intValue() == ResultCode.ATTRIBUTE_OR_VALUE_EXISTS_INT_VALUE)) {
                        log.warn("Session entry update attempt '{}' was unsuccessfull", i);
                        continue;
                    }
                }

                throw ex;
            }
        }

        log.error("Session entry update attempt was unsuccessfull after '{}' attempts", maxAttempts);
        throw lastException;
    }

    public void updateSessionIdIfNeeded(SessionId sessionId, boolean modified) {
        updateSessionId(sessionId, true, false, modified);
    }

    private boolean isPersisted(List<Prompt> prompts) {
        if (prompts != null && prompts.contains(Prompt.NONE)) {
            final Boolean persistOnPromptNone = appConfiguration.getSessionIdPersistOnPromptNone();
            return persistOnPromptNone != null && persistOnPromptNone;
        }
        return true;
    }

    private String dn(String p_id) {
        final String baseDn = getBaseDn();
        final StringBuilder sb = new StringBuilder();
        if (Util.allNotBlank(p_id, getBaseDn())) {
            sb.append("oxAuthSessionId=").append(p_id).append(",").append(baseDn);
        }
        return sb.toString();
    }

    public SessionId getSessionById(String sessionId) {
        return getFromCache(sessionId);
    }

    public SessionId getSessionId(String sessionId) {
        if (StringHelper.isEmpty(sessionId)) {
            return null;
        }

        try {
            final SessionId entity = getSessionById(sessionId);
            log.trace("Try to get session by id: {} ...", sessionId);
            if (entity != null) {
                log.trace("Session dn: {}", entity.getDn());

                if (isSessionValid(entity)) {
                    return entity;
                }
            }
        } catch (Exception ex) {
            log.trace(ex.getMessage(), ex);
        }

        log.trace("Failed to get session by id: {}", sessionId);
        return null;
    }

    private String getBaseDn() {
        return staticConfiguration.getBaseDn().getSessionId();
    }

    public boolean remove(SessionId sessionId) {
        try {
            cacheService.remove(null, sessionId.getId());
        } catch (Exception e) {
            log.error(e.getMessage(), e);

            return false;
        }
        return true;
    }

    public void remove(List<SessionId> list) {
        for (SessionId id : list) {
            try {
                remove(id);
            } catch (Exception e) {
                log.error("Failed to remove entry", e);
            }
        }
    }

    public boolean isSessionValid(SessionId sessionId) {
        if (sessionId == null) {
            return false;
        }

        final long sessionInterval = TimeUnit.SECONDS.toMillis(appConfiguration.getSessionIdUnusedLifetime());
        final long sessionUnauthenticatedInterval = TimeUnit.SECONDS.toMillis(appConfiguration.getSessionIdUnauthenticatedUnusedLifetime());

        final long timeSinceLastAccess = System.currentTimeMillis() - sessionId.getLastUsedAt().getTime();
        if (timeSinceLastAccess > sessionInterval && appConfiguration.getSessionIdUnusedLifetime() != -1) {
            return false;
        }
        if (sessionId.getState() == SessionIdState.UNAUTHENTICATED && timeSinceLastAccess > sessionUnauthenticatedInterval && appConfiguration.getSessionIdUnauthenticatedUnusedLifetime() != -1) {
            return false;
        }

        return true;
    }

    private List<Prompt> getPromptsFromSessionId(final SessionId sessionId) {
        String promptParam = sessionId.getSessionAttributes().get("prompt");
        return Prompt.fromString(promptParam, " ");
    }


    public boolean isSessionIdAuthenticated() {
        SessionId sessionId = getSessionId();

        if (sessionId == null) {
            return false;
        }

        SessionIdState sessionIdState = sessionId.getState();

        if (SessionIdState.AUTHENTICATED.equals(sessionIdState)) {
            return true;
        }

        return false;
    }

    public boolean isNotSessionIdAuthenticated() {
        return !isSessionIdAuthenticated();
    }

    private void auditLogging(SessionId sessionId) {
        HttpServletRequest httpServletRequest = ServerUtil.getRequestOrNull();
        if (httpServletRequest != null) {
            Action action;
            switch (sessionId.getState()) {
                case AUTHENTICATED:
                    action = Action.SESSION_AUTHENTICATED;
                    break;
                case UNAUTHENTICATED:
                    action = Action.SESSION_UNAUTHENTICATED;
                    break;
                default:
                    action = Action.SESSION_UNAUTHENTICATED;
            }
            OAuth2AuditLog oAuth2AuditLog = new OAuth2AuditLog(ServerUtil.getIpAddress(httpServletRequest), action);
            oAuth2AuditLog.setSuccess(true);
            applicationAuditLogger.sendMessage(oAuth2AuditLog);
        }
    }
}