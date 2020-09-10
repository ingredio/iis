package eu.dnetlib.iis.wf.referenceextraction.patent;

import static eu.dnetlib.iis.wf.referenceextraction.patent.OpenPatentWebServiceFacadeFactory.DEFAULT_CHARSET;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Collections;
import java.util.NoSuchElementException;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import eu.dnetlib.iis.referenceextraction.patent.schemas.ImportedPatent;
import eu.dnetlib.iis.wf.importer.HttpClientUtils;

/**
 * Remote EPO endpoint based patent service facade.
 * 
 * @author mhorst
 *
 */
public class OpenPatentWebServiceFacade implements PatentServiceFacade {
    
    private static final long serialVersionUID = -9154710658560662015L;

    private static final Logger log = LoggerFactory.getLogger(OpenPatentWebServiceFacade.class);
    
    private String authUriRoot;
    
    private String opsUriRoot;
    
    // security related 
    private String currentSecurityToken;

    private String consumerCredential;
    
    private long throttleSleepTime;
    
    private int maxRetriesCount;

    // fields to be reinitialized after deserialization
    private transient JsonParser jsonParser;

    private transient CloseableHttpClient httpClient;

    private transient HttpHost authHost;

    private transient HttpHost opsHost;
    
    /**
     * Serialization / deserialization details.
     */
    private SerDe serDe;

    
    // ------------------- CONSTRUCTORS ------------------------
    
    public OpenPatentWebServiceFacade(ConnectionDetails connDetails) {
        
        this(buildHttpClient(connDetails.getConnectionTimeout(), connDetails.getReadTimeout()),
                new HttpHost(connDetails.getAuthHostName(), connDetails.getAuthPort(), connDetails.getAuthScheme()),
                connDetails.getAuthUriRoot(),
                new HttpHost(connDetails.getOpsHostName(), connDetails.getOpsPort(), connDetails.getOpsScheme()),
                connDetails.getOpsUriRoot(), connDetails.getConsumerCredential(), connDetails.getThrottleSleepTime(),
                connDetails.getMaxRetriesCount(), new JsonParser());
        
        // persisting for further serialization and deserialization
        this.serDe = new SerDe(connDetails.getConnectionTimeout(), connDetails.getReadTimeout(),
                connDetails.getAuthHostName(), connDetails.getAuthPort(), connDetails.getAuthScheme(),
                connDetails.getOpsHostName(), connDetails.getOpsPort(), connDetails.getOpsScheme());
    }
    
    /**
     * Using this constructor simplifies object instantiation but also makes the whole object non-serializable.
     */
    protected OpenPatentWebServiceFacade(CloseableHttpClient httpClient, HttpHost authHost, String authUriRoot, HttpHost opsHost,
            String opsUriRoot, String consumerCredential, long throttleSleepTime, int maxRetriesCount,
            JsonParser jsonParser) {
        this.httpClient = httpClient;
        this.authHost = authHost;
        this.authUriRoot = authUriRoot;
        this.opsHost = opsHost;
        this.opsUriRoot = opsUriRoot;
        this.consumerCredential = consumerCredential;
        this.throttleSleepTime = throttleSleepTime;
        this.maxRetriesCount = maxRetriesCount;
        this.jsonParser = jsonParser;
    }

    // ------------------- LOGIC----------------------------
    
    @Override
    public String getPatentMetadata(ImportedPatent patent) throws Exception {
        return getPatentMetadata(patent, getSecurityToken(), 0);
    }

    // ------------------- PRIVATE -------------------------
    
    /**
     * Retrieves patent metadata from EPO endpoint.
     * 
     * This method is recursive and requires response entity to be consumed in order
     * not to hit the ConnectionPoolTimeoutException when connecting the same host
     * more than 2 times within recursion (e.g. when reattepmting).
     */
    private String getPatentMetadata(ImportedPatent patent, String securityToken, int retryCount) throws Exception {
        
        if (retryCount > maxRetriesCount) {
            throw new PatentServiceException("number of maximum retries exceeded: " + maxRetriesCount);
        }

        HttpRequest httpRequest = buildPatentMetaRequest(patent, securityToken, opsUriRoot);

        try (CloseableHttpResponse httpResponse = httpClient.execute(opsHost, httpRequest)) {
        
            int statusCode = httpResponse.getStatusLine().getStatusCode();

            switch (statusCode) {
            case 200: {
                HttpEntity entity = httpResponse.getEntity();
                if (entity == null) {
                    throw new PatentServiceException(
                            "got empty entity in response, full status: " + httpResponse.getStatusLine());
                }
                return EntityUtils.toString(entity);
            }
            case 400: {
                log.info("got 400 HTTP code in response, potential reason: access token invalid or expired, "
                        + "server response: {}", EntityUtils.toString(httpResponse.getEntity()));
                return getPatentMetadata(patent, reauthenticate(), ++retryCount);
            }
            case 403: {
                log.warn("got 403 HTTP code in response, potential reason: endpoint rate limit reached. Delaying for {} ms, "
                        + "server response: {}", throttleSleepTime, EntityUtils.toString(httpResponse.getEntity()));
                Thread.sleep(throttleSleepTime);
                return getPatentMetadata(patent, securityToken, ++retryCount);
            }
            case 404: {
                throw new NoSuchElementException("unable to find element at: " + httpRequest.getRequestLine());
            }
            default: {
                throw new PatentServiceException(String.format(
                        "got unhandled HTTP status code when accessing endpoint: %d, full status: %s, server response: %s",
                        statusCode, httpResponse.getStatusLine(), EntityUtils.toString(httpResponse.getEntity())));
            }
            }
        }
    }

    // -------------------------- PRIVATE -------------------------

    private void reinitialize(SerDe serDe, String authUriRoot, String opsUriRoot,
            String consumerCredential, long throttleSleepTime, int maxRetriesCount) {

        this.serDe = serDe;
        
        this.httpClient = buildHttpClient(serDe.connectionTimeout, serDe.readTimeout);
        this.authHost = new HttpHost(serDe.authHostName, serDe.authPort, serDe.authScheme);
        this.opsHost = new HttpHost(serDe.opsHostName, serDe.opsPort, serDe.opsScheme);
        
        this.authUriRoot = authUriRoot;
        this.opsUriRoot = opsUriRoot;
        
        this.consumerCredential = consumerCredential;
        this.throttleSleepTime = throttleSleepTime;
        this.maxRetriesCount = maxRetriesCount;
        
        this.jsonParser = new JsonParser();
    }
    
    /**
     * Builds HTTP client issuing requests to SH endpoint.
     */
    protected static CloseableHttpClient buildHttpClient(int connectionTimeout, int readTimeout) {
        return HttpClientUtils.buildHttpClient(connectionTimeout, readTimeout);
    }
    
    protected String getSecurityToken() throws Exception {
        if (StringUtils.isNotBlank(this.currentSecurityToken)) {
            return currentSecurityToken;
        } else {
            return reauthenticate();
        }
    }

    protected String reauthenticate() throws Exception {
        currentSecurityToken = authenticate();
        return currentSecurityToken;
    }

    /**
     * Handles authentication operation resulting in a generation of security token.
     * Never returns null.
     * 
     * @throws IOException
     */
    private String authenticate() throws Exception {

        try (CloseableHttpResponse httpResponse = httpClient.execute(authHost, buildAuthRequest(consumerCredential, authUriRoot))) {
            int statusCode = httpResponse.getStatusLine().getStatusCode();

            if (statusCode == 200) {
                String jsonContent = IOUtils.toString(httpResponse.getEntity().getContent(), DEFAULT_CHARSET);

                JsonObject jsonObject = jsonParser.parse(jsonContent).getAsJsonObject();
                JsonElement accessToken = jsonObject.get("access_token");

                if (accessToken == null) {
                    throw new PatentServiceException("access token is missing: " + jsonContent);
                } else {
                    return accessToken.getAsString();
                }
            } else {
                throw new PatentServiceException(String.format(
                        "Authentication failed! HTTP status code when accessing endpoint: %d, full status: %s, server response: %s",
                        statusCode, httpResponse.getStatusLine(), EntityUtils.toString(httpResponse.getEntity())));
            } 
        }
        
        
    }

    protected static HttpRequest buildAuthRequest(String consumerCredential, String uriRoot) {
        HttpPost httpRequest = new HttpPost(uriRoot);
        httpRequest.addHeader("Authorization", "Basic " + consumerCredential);
        BasicNameValuePair grantType = new BasicNameValuePair("grant_type", "client_credentials");
        httpRequest.setEntity(new UrlEncodedFormEntity(Collections.singletonList(grantType), DEFAULT_CHARSET));
        return httpRequest;
    }

    protected static HttpRequest buildPatentMetaRequest(ImportedPatent patent, String bearerToken, String urlRoot) {
        HttpGet httpRequest = new HttpGet(getPatentMetaUrl(patent, urlRoot));
        httpRequest.addHeader("Authorization", "Bearer " + bearerToken);
        return httpRequest;
    }

    protected static String getPatentMetaUrl(ImportedPatent patent, String urlRoot) {
        StringBuilder strBuilder = new StringBuilder(urlRoot);
        if (!urlRoot.endsWith("/")) {
            strBuilder.append('/');
        }
        strBuilder.append(patent.getPublnAuth());
        strBuilder.append('.');
        strBuilder.append(patent.getPublnNr());
        strBuilder.append('.');
        strBuilder.append(patent.getPublnKind());
        strBuilder.append("/biblio");
        return strBuilder.toString();
    }
    
    // -------------------------- SerDe --------------------------------
    
    private void writeObject(ObjectOutputStream oos) throws IOException {
        if (this.serDe == null) {
            throw new IOException("unable to serialize object: "
                    + "http connection related details are missing!");
        }
        oos.defaultWriteObject();
        oos.writeObject(this.serDe);
        oos.writeObject(this.authUriRoot);
        oos.writeObject(this.opsUriRoot);
        oos.writeObject(this.consumerCredential);
        oos.writeObject(this.throttleSleepTime);
        oos.writeObject(this.maxRetriesCount);
    }
    
    private void readObject(ObjectInputStream ois) throws ClassNotFoundException, IOException {
        ois.defaultReadObject();
        reinitialize((SerDe) ois.readObject(), 
                (String) ois.readObject(), (String) ois.readObject(), 
                (String) ois.readObject(), (Long) ois.readObject(), (Integer) ois.readObject());
    }
    
    // -------------------------- INNER CLASS --------------------------
    
    static class SerDe implements Serializable {
        
        private static final long serialVersionUID = 1289144732356257009L;
        
        // http client related
        private int connectionTimeout;

        private int readTimeout;
        
        // EPO endpoints
        private String authHostName;
        
        private int authPort;

        private String authScheme;
        
        private String opsHostName;    
        
        private int opsPort;
        
        private String opsScheme;
        
        public SerDe(int connectionTimeout, int readTimeout, 
                String authHostName, int authPort, String authScheme,
                String opsHostName, int opsPort, String opsScheme) {
            this.connectionTimeout = connectionTimeout;
            this.readTimeout = readTimeout;
            this.authHostName = authHostName;
            this.authPort = authPort;
            this.authScheme = authScheme;
            this.opsHostName = opsHostName;
            this.opsPort = opsPort;
            this.opsScheme = opsScheme;
        }
    }

}
