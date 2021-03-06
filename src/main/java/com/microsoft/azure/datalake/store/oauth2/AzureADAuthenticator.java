/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License.
 * See License.txt in the project root for license information.
 */

package com.microsoft.azure.datalake.store.oauth2;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.microsoft.azure.datalake.store.QueryParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Date;
import java.util.Hashtable;

/**
 * This class provides convenience methods to obtain AAD tokens. While convenient, it is not necessary to
 * use these methods to obtain the tokens. Customers can use any other method (e.g., using the adal4j client)
 * to obtain tokens.
 */

public class AzureADAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(AzureADAuthenticator.class.getName());
    static final String resource = "https://datalake.azure.net/";

    /**
     * gets Azure Active Directory token using the user ID and password of a service principal (that is, Web App
     * in Azure Active Directory).
     * <P>
     * Azure Active Directory allows users to set up a web app as a service principal. Users can optionally
     * obtain service principal keys from AAD. This method gets a token using a service principal's client ID
     * and keys. In addition, it needs the token endpoint associated with the user's directory.
     * </P>
     *
     *
     * @param authEndpoint the OAuth 2.0 token endpoint associated with the user's directory
     *                     (obtain from Active Directory configuration)
     * @param clientId the client ID (GUID) of the client web app obtained from Azure Active Directory configuration
     * @param clientSecret the secret key of the client web app
     * @return {@link AzureADToken} obtained using the creds
     * @throws IOException throws IOException if there is a failure in connecting to Azure AD
     */
    public static AzureADToken getTokenUsingClientCreds(String authEndpoint, String clientId, String clientSecret)
            throws IOException
    {
        QueryParams qp = new QueryParams();

        qp.add("resource", resource);
        qp.add("grant_type","client_credentials");
        qp.add("client_id", clientId);
        qp.add("client_secret", clientSecret);
        log.debug("AADToken: starting to fetch token using client creds for client ID " + clientId );

        return getTokenCall(authEndpoint, qp.serialize());
    }

    /**
     * Gets AAD token from the local virtual machine's VM extension. This only works on an Azure VM with MSI extension
     * enabled.
     *
     * @param localPort port at which the MSI extension is running. If 0 or negative number is specified, then assume
     *                  default port number of 50342.
     * @param tenantGuid (optional) The guid of the AAD tenant. Can be {@code null}.
     * @return {@link AzureADToken} obtained using the creds
     * @throws IOException throws IOException if there is a failure in obtaining the token
     */
    public static AzureADToken getTokenFromMsi(int localPort, String tenantGuid) throws IOException {
        if (localPort <= 0) localPort = 50342;
        String authEndpoint  = "http://localhost:" + localPort + "/oauth2/token";

        QueryParams qp = new QueryParams();
        qp.add("resource", resource);

        if (tenantGuid != null && tenantGuid.length() > 0) {
            String authority = "https://login.microsoftonline.com/" + tenantGuid;
            qp.add("authority", authority);
        }

        Hashtable<String, String> headers = new Hashtable<String, String>();
        headers.put("Metadata", "true");

        log.debug("AADToken: starting to fetch token using MSI");
        return getTokenCall(authEndpoint, qp.serialize(), headers);
    }

    /**
     * gets Azure Active Directory token using refresh token
     *
     * @param clientId the client ID (GUID) of the client web app obtained from Azure Active Directory configuration
     * @param refreshToken the refresh token
     * @return {@link AzureADToken} obtained using the refresh token
     * @throws IOException throws IOException if there is a failure in connecting to Azure AD
     */
    public static AzureADToken getTokenUsingRefreshToken(String clientId, String refreshToken)
            throws IOException
    {
        String authEndpoint = "https://login.microsoftonline.com/Common/oauth2/token";

        QueryParams qp = new QueryParams();
        qp.add("grant_type", "refresh_token");
        qp.add("refresh_token", refreshToken);
        if (clientId != null) qp.add("client_id", clientId);
        log.debug("AADToken: starting to fetch token using refresh token for client ID " + clientId );

        return getTokenCall(authEndpoint, qp.serialize());
    }

    /**
     * gets Azure Active Directory token using the user's username and password. This only
     * works if the identity can be authenticated directly by microsoftonline.com. It will likely
     * not work if the domain is federated and/or multi-factor authentication or other form of
     * strong authentication is configured for the user.
     * <P>
     * Due to security concerns with user ID and password,this should only be used in limited test
     * circumstances, and for test/dummy users only.
     * </P>
     * @param clientId the client ID (GUID) of the client web app obtained from Azure Active Directory configuration
     * @param username the user name of the user
     * @param password the password of the user
     * @return {@link AzureADToken} obtained using the user's creds
     * @throws IOException throws IOException if there is a failure in connecting to Azure AD
     */
    public static AzureADToken getTokenUsingUserCreds(String clientId, String username, String password)
            throws IOException
    {
        String authEndpoint = "https://login.microsoftonline.com/Common/oauth2/token";

        QueryParams qp = new QueryParams();
        qp.add("grant_type", "password");
        qp.add("resource", resource);
        qp.add("scope", "openid");
        qp.add("client_id", clientId);
        qp.add("username",username);
        qp.add("password",password);
        log.debug("AADToken: starting to fetch token using username for user " + username );

        return getTokenCall(authEndpoint, qp.serialize());
    }

    private static AzureADToken getTokenCall(String authEndpoint, String body, Hashtable<String, String> headers)
            throws IOException {
        AzureADToken token;

        URL url = new URL(authEndpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");

        if (headers!=null && headers.size() > 0) {
            for (String name : headers.keySet()) {
                conn.setRequestProperty(name, headers.get(name));
            }
        }

        conn.setDoOutput(true);
        conn.getOutputStream().write(body.getBytes("UTF-8"));

        int httpResponseCode = conn.getResponseCode();
        if (httpResponseCode == 200) {
            InputStream httpResponseStream = conn.getInputStream();
            token = parseTokenFromStream(httpResponseStream);
        } else {
            log.debug("AADToken: HTTP connection failed for getting token from AzureAD. Http response: " + httpResponseCode + " " + conn.getResponseMessage());
            throw new IOException("Failed to acquire token from AzureAD. Http response: " + httpResponseCode + " " + conn.getResponseMessage());
        }
        return token;
    }

    private static AzureADToken getTokenCall(String authEndpoint, String body)
            throws IOException
    {
        return getTokenCall(authEndpoint, body, null);
    }

    private static AzureADToken parseTokenFromStream(InputStream httpResponseStream) throws IOException {
        AzureADToken token = new AzureADToken();
        try {
            int expiryPeriod = 0;

            JsonFactory jf = new JsonFactory();
            JsonParser jp = jf.createParser(httpResponseStream);
            String fieldName, fieldValue;
            jp.nextToken();
            while (jp.hasCurrentToken()) {
                if (jp.getCurrentToken() == JsonToken.FIELD_NAME) {
                    fieldName = jp.getCurrentName();
                    jp.nextToken();  // field value
                    fieldValue = jp.getText();

                    if (fieldName.equals("access_token")) token.accessToken = fieldValue;
                    if (fieldName.equals("expires_in")) expiryPeriod = Integer.parseInt(fieldValue);
                }
                jp.nextToken();
            }
            jp.close();
            long expiry = System.currentTimeMillis();
            expiry = expiry + expiryPeriod * 1000L; // convert expiryPeriod to milliseconds and add
            token.expiry = new Date(expiry);
            log.debug("AADToken: fetched token with expiry " + token.expiry.toString());
        } catch (Exception ex) {
            log.debug("AADToken: got exception when parsing json token " + ex.toString());
            throw ex;
        } finally {
            httpResponseStream.close();
        }
        return token;
    }
}


