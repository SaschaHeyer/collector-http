/* Copyright 2010-2017 Norconex Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.norconex.collector.http.fetch.impl;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import javax.xml.stream.XMLStreamException;

import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.NullOutputStream;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.AuthCache;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import com.norconex.collector.core.CollectorException;
import com.norconex.collector.core.data.CrawlState;
import com.norconex.collector.http.data.HttpCrawlState;
import com.norconex.collector.http.doc.HttpDocument;
import com.norconex.collector.http.doc.HttpMetadata;
import com.norconex.collector.http.fetch.HttpFetchResponse;
import com.norconex.collector.http.fetch.IHttpDocumentFetcher;
import com.norconex.commons.lang.config.IXMLConfigurable;
import com.norconex.commons.lang.config.XMLConfigurationUtil;
import com.norconex.commons.lang.file.ContentType;
import com.norconex.commons.lang.url.HttpURL;
import com.norconex.commons.lang.xml.EnhancedXMLStreamWriter;
import com.norconex.importer.doc.ContentTypeDetector;
import com.norconex.importer.util.CharsetUtil;

/**
 * <p>
 * Default implementation of {@link IHttpDocumentFetcher}.
 * </p>
 * <h3>Content type and character encoding</h3>
 * <p>
 * The default behavior of the HTTP Collector to identify the content type
 * and character encoding of a document is to rely on the
 * "<a href="https://www.w3.org/Protocols/rfc1341/4_Content-Type.html">Content-Type</a>"
 * HTTP response header.  Web servers can sometimes return invalid
 * or missing content type and character encoding information. Since 2.7.0,
 * you can optionally decide not to trust web servers HTTP responses and have
 * the collector perform its own content type and encoding detection.
 * Such detection can be enabled with {@link #setDetectContentType(boolean)}
 * and {@link #setDetectCharset(boolean)}.
 * </p>
 *
 * <h3>XML configuration usage:</h3>
 * <pre>
 *  &lt;documentFetcher
 *      class="com.norconex.collector.http.fetch.impl.GenericDocumentFetcher"
 *      detectContentType="[false|true]" detectCharset="[false|true]"&gt;
 *    &lt;validStatusCodes&gt;(defaults to 200)&lt;/validStatusCodes&gt;
 *    &lt;notFoundStatusCodes&gt;(defaults to 404)&lt;/notFoundStatusCodes&gt;
 *    &lt;headersPrefix&gt;(string to prefix headers)&lt;/headersPrefix&gt;
 *  &lt;/documentFetcher&gt;
 * </pre>
 * <p>
 * The "validStatusCodes" and "notFoundStatusCodes" elements expect a
 * coma-separated list of HTTP response code.  If a code is added in both
 * elements, the valid list takes precedence.
 * </p>
 * <p>
 * The "notFoundStatusCodes" element was added in 2.2.0.
 * </p>
 *
 * <h4>Usage example:</h4>
 * <p>
 * The following configures the document fetcher to not trust HTTP response
 * headers to identify the content type and encoding, but try to detect
 * them instead.
 * </p>
 * <pre>
 *  &lt;documentFetcher detectContentType="true" detectCharset="true"/&gt;
 * </pre>
 *
 * @author Pascal Essiembre
 */
public class GenericDocumentFetcher
        implements IHttpDocumentFetcher, IXMLConfigurable {

    private static final Logger LOG = LogManager.getLogger(
			GenericDocumentFetcher.class);

    private int[] validStatusCodes;
    private int[] notFoundStatusCodes =
            GenericMetadataFetcher.DEFAULT_NOT_FOUND_STATUS_CODES;
    private String headersPrefix;
    private boolean detectContentType;
    private boolean detectCharset;
    private final ContentTypeDetector contentTypeDetector = new ContentTypeDetector();

    private final AuthCache authCache = new BasicAuthCache();
    private Object userToken;

    public GenericDocumentFetcher() {
        this(GenericMetadataFetcher.DEFAULT_VALID_STATUS_CODES);
    }
    public GenericDocumentFetcher(int[] validStatusCodes) {
        super();
        setValidStatusCodes(validStatusCodes);
    }

    public int[] getValidStatusCodes() {
        return ArrayUtils.clone(validStatusCodes);
    }
    public final void setValidStatusCodes(int... validStatusCodes) {
        this.validStatusCodes = ArrayUtils.clone(validStatusCodes);
    }
    /**
     * Gets HTTP status codes to be considered as "Not found" state.
     * Default is 404.
     * @return "Not found" codes
     * @since 2.2.0
     */
    public int[] getNotFoundStatusCodes() {
        return ArrayUtils.clone(notFoundStatusCodes);
    }
    /**
     * Sets HTTP status codes to be considered as "Not found" state.
     * @param notFoundStatusCodes "Not found" codes
     * @since 2.2.0
     */
    public final void setNotFoundStatusCodes(int... notFoundStatusCodes) {
        this.notFoundStatusCodes = ArrayUtils.clone(notFoundStatusCodes);
    }
    public String getHeadersPrefix() {
        return headersPrefix;
    }
    public void setHeadersPrefix(String headersPrefix) {
        this.headersPrefix = headersPrefix;
    }
    /**
     * Gets whether content type is detected instead of relying on
     * HTTP response header.
     * @return <code>true</code> to enable detection
     * @since 2.7.0
     */
	public boolean isDetectContentType() {
        return detectContentType;
    }
	/**
	 * Sets whether content type is detected instead of relying on
     * HTTP response header.
	 * @param detectContentType <code>true</code> to enable detection
     * @since 2.7.0
	 */
    public void setDetectContentType(boolean detectContentType) {
        this.detectContentType = detectContentType;
    }
    /**
     * Gets whether character encoding is detected instead of relying on
     * HTTP response header.
     * @return <code>true</code> to enable detection
     * @since 2.7.0
     */
    public boolean isDetectCharset() {
        return detectCharset;
    }
    /**
     * Sets whether character encoding is detected instead of relying on
     * HTTP response header.
     * @param detectCharset <code>true</code> to enable detection
     * @since 2.7.0
     */
    public void setDetectCharset(boolean detectCharset) {
        this.detectCharset = detectCharset;
    }

    @Override
	public HttpFetchResponse fetchDocument(
	        HttpClient httpClient, HttpDocument doc) {
	    //TODO replace signature with Writer class.
	    LOG.debug("Fetching document: " + doc.getReference());
	    HttpRequestBase method = null;
	    try {
	        method = createUriRequest(doc);

	        HttpClientContext ctx = HttpClientContext.create();
	        // auth cache
	        ctx.setAuthCache(authCache);
	        // user token
	        if (userToken != null) {
	            ctx.setUserToken(userToken);
	        }

	        // Execute the method.
            HttpResponse response = httpClient.execute(method, ctx);
            int statusCode = response.getStatusLine().getStatusCode();
            String reason = response.getStatusLine().getReasonPhrase();

            InputStream is = response.getEntity().getContent();

            // VALID http response
            if (ArrayUtils.contains(validStatusCodes, statusCode)) {
                //--- Fetch headers ---
                Header[] headers = response.getAllHeaders();
                for (int i = 0; i < headers.length; i++) {
                    Header header = headers[i];
                    String name = header.getName();
                    if (StringUtils.isNotBlank(headersPrefix)) {
                        name = headersPrefix + name;
                    }
                    if (doc.getMetadata().getString(name) == null) {
                        doc.getMetadata().addString(name, header.getValue());
                    }
                }

                //--- Fetch body
                doc.setContent(doc.getContent().newInputStream(is));

                //read a copy to force caching and then close the HTTP stream
                IOUtils.copy(doc.getContent(), new NullOutputStream());

                userToken = ctx.getUserToken();

                performDetection(doc);
                return new HttpFetchResponse(
                        HttpCrawlState.NEW, statusCode, reason);
            }

            // INVALID http response
            if (LOG.isTraceEnabled()) {
                LOG.trace("Rejected response content: "
                        + IOUtils.toString(is, StandardCharsets.UTF_8));
                IOUtils.closeQuietly(is);
            } else {
                // read response anyway to be safer, but ignore content
                BufferedInputStream bis = new BufferedInputStream(is);
                int result = bis.read();
                while(result != -1) {
                  result = bis.read();
                }
                IOUtils.closeQuietly(bis);
            }

            if (ArrayUtils.contains(notFoundStatusCodes, statusCode)) {
                return new HttpFetchResponse(
                        HttpCrawlState.NOT_FOUND, statusCode, reason);
            }
            LOG.debug("Unsupported HTTP Response: "
                    + response.getStatusLine());
            return new HttpFetchResponse(
                    CrawlState.BAD_STATUS, statusCode, reason);
        } catch (Exception e) {
            if (LOG.isDebugEnabled()) {
                LOG.info("Cannot fetch document: " + doc.getReference()
                        + " (" + e.getMessage() + ")", e);
            } else {
                LOG.info("Cannot fetch document: " + doc.getReference()
                        + " (" + e.getMessage() + ")");
            }
            throw new CollectorException(e);
        } finally {
            if (method != null) {
                method.releaseConnection();
            }
        }
	}

    private void performDetection(HttpDocument doc) throws IOException {
        if (detectContentType) {
            ContentType ct = contentTypeDetector.detect(
                    doc.getContent(), doc.getReference());
            if (ct != null) {
                doc.getMetadata().setString(
                        HttpMetadata.COLLECTOR_CONTENT_TYPE, ct.toString());
            }
        }
        if (detectCharset) {
            String charset = CharsetUtil.detectCharset(doc.getContent());
            if (StringUtils.isNotBlank(charset)) {
                doc.getMetadata().setString(
                        HttpMetadata.COLLECTOR_CONTENT_ENCODING, charset);
            }
        }
    }

	/**
	 * Creates the HTTP request to be executed.  Default implementation
	 * returns an {@link HttpGet} request around the document reference.
	 * This method can be overwritten to return another type of request,
	 * add HTTP headers, etc.
	 * @param doc document to fetch
	 * @return HTTP request
	 */
	protected HttpRequestBase createUriRequest(HttpDocument doc) {
	    URI uri = HttpURL.toURI(doc.getReference());
	    if (LOG.isDebugEnabled()) {
	        LOG.debug("Encoded URI: " + uri);
	    }
	    return new HttpGet(uri);
	}

    @Override
    public void loadFromXML(Reader in) {
        XMLConfiguration xml = XMLConfigurationUtil.newXMLConfiguration(in);

        String validCodes = xml.getString("validStatusCodes");
        int[] intValidCodes = validStatusCodes;
        if (StringUtils.isNotBlank(validCodes)) {
            String[] strCodes = validCodes.split(",");
            intValidCodes = new int[strCodes.length];
            for (int i = 0; i < strCodes.length; i++) {
                String code = strCodes[i];
                intValidCodes[i] = Integer.parseInt(code);
            }
        }
        setValidStatusCodes(intValidCodes);

        String notFoundCodes = xml.getString("notFoundStatusCodes");
        int[] intNFCodes = notFoundStatusCodes;
        if (StringUtils.isNotBlank(notFoundCodes)) {
            String[] strCodes = notFoundCodes.split(",");
            intNFCodes = new int[strCodes.length];
            for (int i = 0; i < strCodes.length; i++) {
                String code = strCodes[i];
                intNFCodes[i] = Integer.parseInt(code);
            }
        }
        setNotFoundStatusCodes(intNFCodes);

        setHeadersPrefix(xml.getString("headersPrefix"));
        setDetectContentType(
                xml.getBoolean("[@detectContentType]", isDetectContentType()));
        setDetectCharset(xml.getBoolean("[@detectCharset]", isDetectCharset()));

    }
    @Override
    public void saveToXML(Writer out) throws IOException {
        try {
            EnhancedXMLStreamWriter writer = new EnhancedXMLStreamWriter(out);
            writer.writeStartElement("documentFetcher");
            writer.writeAttribute("class", getClass().getCanonicalName());
            writer.writeAttributeBoolean(
                    "detectContentType", isDetectContentType());
            writer.writeAttributeBoolean("detectCharset", isDetectCharset());

            writer.writeElementString("validStatusCodes",
                    StringUtils.join(validStatusCodes, ','));
            writer.writeElementString("notFoundStatusCodes",
                    StringUtils.join(notFoundStatusCodes, ','));
            writer.writeElementString("headersPrefix", headersPrefix);

            writer.writeEndElement();
            writer.flush();
        } catch (XMLStreamException e) {
            throw new IOException("Cannot save as XML.", e);
        }
    }

    @Override
    public boolean equals(final Object other) {
        if (!(other instanceof GenericDocumentFetcher)) {
            return false;
        }
        GenericDocumentFetcher castOther = (GenericDocumentFetcher) other;
        return new EqualsBuilder()
                .append(validStatusCodes, castOther.validStatusCodes)
                .append(notFoundStatusCodes, castOther.notFoundStatusCodes)
                .append(headersPrefix, castOther.headersPrefix)
                .append(detectContentType, castOther.detectContentType)
                .append(detectCharset, castOther.detectCharset)
                .isEquals();
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder()
                .append(validStatusCodes)
                .append(notFoundStatusCodes)
                .append(headersPrefix)
                .append(detectContentType)
                .append(detectCharset)
                .toHashCode();
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.SHORT_PREFIX_STYLE)
                .append("validStatusCodes", validStatusCodes)
                .append("notFoundStatusCodes", notFoundStatusCodes)
                .append("headersPrefix", headersPrefix)
                .append("detectContentType", detectContentType)
                .append("detectCharset", detectCharset)
                .toString();
    }
}