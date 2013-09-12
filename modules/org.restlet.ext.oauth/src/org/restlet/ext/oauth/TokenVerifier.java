/**
 * Copyright 2005-2013 Restlet S.A.S.
 * 
 * The contents of this file are subject to the terms of one of the following
 * open source licenses: Apache 2.0 or LGPL 3.0 or LGPL 2.1 or CDDL 1.0 or EPL
 * 1.0 (the "Licenses"). You can select the license that you prefer but you may
 * not use this file except in compliance with one of these Licenses.
 * 
 * You can obtain a copy of the Apache 2.0 license at
 * http://www.opensource.org/licenses/apache-2.0
 * 
 * You can obtain a copy of the LGPL 3.0 license at
 * http://www.opensource.org/licenses/lgpl-3.0
 * 
 * You can obtain a copy of the LGPL 2.1 license at
 * http://www.opensource.org/licenses/lgpl-2.1
 * 
 * You can obtain a copy of the CDDL 1.0 license at
 * http://www.opensource.org/licenses/cddl1
 * 
 * You can obtain a copy of the EPL 1.0 license at
 * http://www.opensource.org/licenses/eclipse-1.0
 * 
 * See the Licenses for the specific language governing permissions and
 * limitations under the Licenses.
 * 
 * Alternatively, you can obtain a royalty free commercial license with less
 * limitations, transferable or non-transferable, directly at
 * http://www.restlet.com/products/restlet-framework
 * 
 * Restlet is a registered trademark of Restlet S.A.S.
 */

package org.restlet.ext.oauth;

import java.util.logging.Level;
import java.util.logging.Logger;
import org.json.JSONException;
import org.json.JSONObject;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.CacheDirective;
import org.restlet.data.ChallengeResponse;
import org.restlet.data.ChallengeScheme;
import org.restlet.data.ClientInfo;
import org.restlet.data.Form;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.Reference;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.ext.oauth.internal.Scopes;
import org.restlet.representation.Representation;
import org.restlet.resource.ClientResource;
import org.restlet.security.User;
import org.restlet.security.Verifier;

/**
 * Verifier for OAuth 2.0 Protected Resources. Typically use with
 * ChallengeAuthenticator. "Bearer" and "MAC" challenge schemes are may
 * supported.
 * 
 * @author Shotaro Uchida <fantom@xmaker.mx>
 * @see <a href="http://tools.ietf.org/html/draft-ietf-oauth-v2-bearer-22">
 *      Bearer Token Usage</a>
 * @see <a href="http://tools.ietf.org/html/draft-ietf-oauth-v2-http-mac-01">
 *      MAC Access Authentication</a>
 */
public class TokenVerifier implements Verifier {

    // public static final ChallengeScheme HTTP_BEARER =
    // new ChallengeScheme("HTTP_BEARER", "Bearer",
    // "The OAuth 2.0 Authorization Framework: Bearer Token Usage");
    // public static final ChallengeScheme HTTP_MAC =
    // new ChallengeScheme("HTTP_MAC", "MAC", "MAC Access Authentication");

    private Reference authReference;

    private boolean acceptBodyMethod = false; // 2.2. Form-Encoded Body
                                              // Parameter

    private boolean acceptQueryMethod = false; // 2.3. URI Query Parameter

    private static final Logger logger = Logger.getLogger(TokenVerifier.class
            .getName());

    public TokenVerifier(Reference authReference) {
        this.authReference = authReference;
    }

    public int verify(Request request, Response response) {
        final JSONObject authRequest;

        try {
            ChallengeResponse cr = request.getChallengeResponse();
            if (cr == null) {
                // Try Bearer alternative methods
                String bearer = null;
                if (acceptBodyMethod) {
                    bearer = getAccessTokenFromBody(request);
                }
                if (bearer == null && acceptQueryMethod) {
                    bearer = getAccessTokenFromQuery(request);
                    if (bearer != null) {
                        OAuthServerResource.addCacheDirective(response,
                                CacheDirective.privateInfo());
                    }
                }
                if (bearer == null) {
                    return RESULT_MISSING;
                }
                logger.config("Verify: Bearer (Alternative)");
                authRequest = createBearerAuthRequest(bearer);
            } else if (ChallengeScheme.HTTP_OAUTH_BEARER.equals(cr.getScheme())) {
                logger.config("Verify: Bearer");
                final String bearer = cr.getRawValue();
                if (bearer == null || bearer.isEmpty()) {
                    return RESULT_MISSING;
                }
                authRequest = createBearerAuthRequest(bearer);
            }/*
              * else if (cr.getScheme().equals(HTTP_MAC)) { // TODO }
              */else {
                return RESULT_UNSUPPORTED;
            }
        } catch (Exception ex) {
            return RESULT_INVALID;
        }

        ClientResource authResource = new ClientResource(authReference);
        JsonRepresentation jsonRepresentation;
        JSONObject jsonResponse;

        try {
            logger.fine("Post auth request to auth resource...");
            Representation resp = authResource.post(new JsonRepresentation(
                    authRequest));
            jsonRepresentation = new JsonRepresentation(resp);
            jsonResponse = jsonRepresentation.getJsonObject();
        } catch (Exception ex) {
            logger.log(Level.SEVERE, null, ex);
            return RESULT_INVALID;
        }

        if (jsonResponse.has(OAuthServerResource.ERROR)) {
            try {
                String error = jsonResponse
                        .getString(OAuthServerResource.ERROR);
                logger.warning(error);
                logger.warning(jsonResponse
                        .getString(OAuthServerResource.ERROR_DESC));
            } catch (JSONException ex) {
                logger.log(Level.SEVERE, null, ex);
            }
            // TODO: Configure challenge request
            return RESULT_INVALID;
        }

        try {
            ClientInfo clientInfo = request.getClientInfo();
            clientInfo.setUser(new User(jsonResponse
                    .getString(OAuthServerResource.USERNAME)));
            clientInfo.setRoles(Scopes.toRoles(jsonResponse
                    .getString(OAuthServerResource.SCOPE)));
        } catch (JSONException ex) {
            return RESULT_INVALID;
        }

        return RESULT_VALID;
    }

    private static JSONObject createBearerAuthRequest(String token)
            throws JSONException {
        JSONObject request = new JSONObject();
        request.put(OAuthServerResource.TOKEN_TYPE,
                OAuthServerResource.TOKEN_TYPE_BEARER);
        request.put(OAuthServerResource.ACCESS_TOKEN, token);
        return request;
    }

    private String getAccessTokenFromQuery(Request request) {
        // Try to find token in URI query
        Form params = request.getOriginalRef().getQueryAsForm();
        String token = params.getFirstValue(OAuthServerResource.ACCESS_TOKEN);
        if (token != null && !token.isEmpty()) {
            logger.fine("Found Bearer Token in URI query.");
            return token;
        }
        return null;
    }

    private String getAccessTokenFromBody(Request request) {
        Method method = request.getMethod();
        if (method.equals(Method.GET)) {
            return null;
        }

        Representation entity = request.getEntity();
        if (entity != null
                && !MediaType.APPLICATION_WWW_FORM
                        .equals(entity.getMediaType())) {
            return null;
        }

        Form form = new Form(request.getEntity());
        final String token = form
                .getFirstValue(OAuthServerResource.ACCESS_TOKEN);
        if (token == null || token.isEmpty()) {
            return null;
        }
        // Restore the body
        request.setEntity(form.getWebRepresentation());
        logger.fine("Found Bearer Token in Body.");
        return token;
    }

    /**
     * @return the acceptBodyMethod
     */
    public boolean isAcceptBodyMethod() {
        return acceptBodyMethod;
    }

    /**
     * @param acceptBodyMethod
     *            the acceptBodyMethod to set
     */
    public void setAcceptBodyMethod(boolean acceptBodyMethod) {
        this.acceptBodyMethod = acceptBodyMethod;
    }

    /**
     * @return the acceptQueryMethod
     */
    public boolean isAcceptQueryMethod() {
        return acceptQueryMethod;
    }

    /**
     * @param acceptQueryMethod
     *            the acceptQueryMethod to set
     */
    public void setAcceptQueryMethod(boolean acceptQueryMethod) {
        this.acceptQueryMethod = acceptQueryMethod;
    }
}
