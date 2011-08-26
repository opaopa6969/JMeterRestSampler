/*  Copyright 2009 Fabrizio Cannizzo
 *
 *  This file is part of JMeterRestSampler.
 *
 *  JMeterRestSampler (http://code.google.com/p/rest-fixture/) is free software:
 *  you can redistribute it and/or modify it under the terms of the
 *  BSD License
 *
 *  You should have received a copy of the BSD License
 *  along with JMeterRestSampler.  If not, see <http://opensource.org/licenses/bsd-license.php>.
 *
 *  If you want to contact the author please see http://smartrics.blogspot.com
 */

package smartrics.jmeter.sampler;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.zip.GZIPInputStream;

import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.URI;
import org.apache.commons.httpclient.URIException;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpOptions;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.methods.HttpTrace;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.AbstractHttpMessage;
import org.apache.jmeter.protocol.http.control.CacheManager;
import org.apache.jmeter.protocol.http.control.CookieManager;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.protocol.http.util.EncoderCache;
import org.apache.jorphan.logging.LoggingManager;
import org.apache.jorphan.util.JOrphanUtils;
import org.apache.log.Logger;

/**
 * Models a REST request for sampling purposes.
 */
public class RestSampler extends HTTPSamplerBase {
	private static final long serialVersionUID = -5877623539165274730L;

	private static final Logger log = LoggingManager.getLoggerForClass();

	public static final String REQUEST_BODY = "RestSampler.request_body";

	public static final String QUERY_STRING = "RestSampler.query_string";

	public static final String RESOURCE = "RestSampler.resource";

	public static final String BASE_HOST = "RestSampler.base_host";

	public static final String REQUEST_HEADERS = "RestSampler.request_headers";

	public RestSampler() {
		log.debug("initialised new RestSampler");
	}

	public void setRequestBody(String data) {
		setProperty(REQUEST_BODY, data);
	}

	public void setRequestHeaders(String headers) {
		setProperty(REQUEST_HEADERS, headers);
	}

	public String getRequestBody() {
		return getPropertyAsString(REQUEST_BODY);
	}

	public String getRequestHeaders() {
		return getPropertyAsString(REQUEST_HEADERS);
	}

	public void setResource(String data) {
		setProperty(RESOURCE, data);
	}

	public String getResource() {
		return getPropertyAsString(RESOURCE);
	}

	public void setQueryString(String data) {
		setProperty(QUERY_STRING, data);
		getArguments().clear();
		parseArguments(data, EncoderCache.URL_ARGUMENT_ENCODING);
	}

	public String getQueryString() {
		return getPropertyAsString(QUERY_STRING);
	}

	public void setHostBaseUrl(final String data) {
		setProperty(BASE_HOST, data);
	}

	public String getHostBaseUrl() {
		return getPropertyAsString(BASE_HOST);
	}

	/**
	 * Returns the full resource URI concatenating the base url and the resource
	 * id. If either components are missing or invalid, it returns a canned
	 * value of <code>"http://undefined.com"</code>
	 */
	public URL getUrl() {
		String validHost = toValidUrl(getHostBaseUrl());
		URL u = toURL("http://undefined.com");
		if (validHost != null && getResource() != null) {
			String fullUrl = validHost + getResource();
			u = toURL(fullUrl);
		}
		return u;
	}

	public String toString() {
		return "Base host url: " + getHostBaseUrl() + ", resource: "
				+ getResource() + ", Method: " + getMethod();
	}

	private String toValidUrl(String u) {
		try {
			URL url = new URL(u);
			String urlStr = url.toString();
			if (urlStr.endsWith("/")) {
				url = toURL(urlStr.substring(0, urlStr.length() - 1));
				urlStr = url.toString();
			}
			return urlStr;
		} catch (MalformedURLException e) {
			return null;
		}
	}

	private URL toURL(String u) {
		try {
			return new URL(u);
		} catch (MalformedURLException e) {
			return null;
		}
	}

	private void overrideHeaders(AbstractHttpMessage httpMethod) {
		String headers = getRequestHeaders();
		String[] header = headers.split(System.getProperty("line.separator"));
		for (String kvp : header) {
			int pos = kvp.indexOf(':');
			if (pos < 0)
				pos = kvp.indexOf('=');
			if (pos > 0) {
				String k = kvp.substring(0, pos).trim();
				String v = "";
				if (kvp.length() > pos + 1)
					v = kvp.substring(pos + 1).trim();
				httpMethod.addHeader(k, v);
			}
		}
	}

	/**
	 * Method invoked by JMeter when a sample needs to happen. It's actually an
	 * indirect call from the main sampler interface. it's resolved in the base
	 * class.
	 * 
	 * This is a copy and paste from the HTTPSampler2 - quick and dirty hack as
	 * that class is not very extensible. The reason to extend and slightly
	 * modify is that I needed to get the body content from a text field in the
	 * GUI rather than a file.
	 */
	protected HTTPSampleResult sample(URL url, String method,
			boolean areFollowingRedirect, int frameDepth) {

		String urlStr = url.toString();

		log.debug("Start : sample " + urlStr);
		log.debug("method " + method);

		HttpRequestBase httpMethod = null;

		HTTPSampleResult res = new HTTPSampleResult();
		res.setMonitor(isMonitor());

		res.setSampleLabel(urlStr); // May be replaced later
		res.setHTTPMethod(method);
		res.setURL(url);
		res.sampleStart(); // Count the retries as well in the time
		HttpClient client = null;
		InputStream instream = null;
		try {
			httpMethod = createHttpMethod(method, urlStr);
			// Set any default request headers
			// setDefaultRequestHeaders(httpMethod);
			// Setup connection
			client = new DefaultHttpClient();
			// Handle the various methods
			if (httpMethod instanceof HttpEntityEnclosingRequestBase) {
				String postBody = sendData((HttpEntityEnclosingRequestBase) httpMethod);
				Charset encoding = Charset.forName(getContentEncoding());
				res.setResponseData(postBody.getBytes(encoding));
			}
			overrideHeaders(httpMethod);
			res.setRequestHeaders(getRequestHeaders());

			HttpResponse response = null;
			try {
				response = client.execute(httpMethod);
			} catch (RuntimeException e) {
				log.error("Exception when executing '" + httpMethod + "'", e);
				throw e;
			}

			HttpEntity responseEntity = response.getEntity();
			
			// Request sent. Now get the response:
			instream = responseEntity.getContent();;

			if (instream != null) {// will be null for HEAD

				org.apache.http.Header responseHeader = response.getFirstHeader(HEADER_CONTENT_ENCODING);
				if (responseHeader != null
						&& ENCODING_GZIP.equals(responseHeader.getValue())) {
					instream = new GZIPInputStream(instream);
				}
				res.setResponseData(readResponse(res, instream,
						(int) responseEntity.getContentLength()));
			}

			res.sampleEnd();
			// Done with the sampling proper.

			// Now collect the results into the HTTPSampleResult:

			res.setSampleLabel(httpMethod.getURI().toString());
			// Pick up Actual path (after redirects)

			int statusCode = response.getStatusLine().getStatusCode();
			res.setResponseCode(Integer.toString(statusCode));
			res.setSuccessful(isSuccessCode(statusCode));

			res.setResponseMessage(response.getStatusLine().getReasonPhrase());

			String ct = null;
			org.apache.http.Header responseHeader = response.getFirstHeader(HEADER_CONTENT_TYPE);
			if (responseHeader != null){// Can be missing, e.g. on redirect
				ct = responseHeader.getValue();
				res.setContentType(ct);// e.g. text/html; charset=ISO-8859-1
				res.setEncodingAndType(ct);
			}

			String responseHeaders = getResponseHeaders(response);
			res.setResponseHeaders(responseHeaders);
			if (res.isRedirect()) {
				final org.apache.http.Header headerLocation = response
						.getFirstHeader(HEADER_LOCATION);
				if (headerLocation == null) { // HTTP protocol violation, but
					// avoids NPE
					throw new IllegalArgumentException(
							"Missing location header");
				}
				res.setRedirectLocation(headerLocation.getValue());
			}

			// If we redirected automatically, the URL may have changed
			if (getAutoRedirects()) {
				res.setURL(new URL(httpMethod.getURI().toString()));
			}

			// Store any cookies received in the cookie manager:
			saveConnectionCookies(response, res.getURL(), getCookieManager());

			// Save cache information
			final CacheManager cacheManager = getCacheManager();
			if (cacheManager != null) {
				HttpMethod getMethod = new ConvertedHttpMethod(response , new URI(url.toString(), false));
				cacheManager.saveDetails(getMethod, res);
			}

			// Follow redirects and download page resources if appropriate:
			res = resultProcessing(areFollowingRedirect, frameDepth, res);

			log.debug("End : sample");
			return res;
		} catch (IllegalArgumentException e)// e.g. some kinds of invalid URL
		{
			res.sampleEnd();
			HTTPSampleResult err = errorResult(e, res);
			err.setSampleLabel("Error: " + url.toString());
			return err;
		} catch (IOException e) {
			res.sampleEnd();
			HTTPSampleResult err = errorResult(e, res);
			err.setSampleLabel("Error: " + url.toString());
			return err;
		} finally {
			JOrphanUtils.closeQuietly(instream);
		}
	}

	private HttpRequestBase createHttpMethod(String method, String urlStr) {
		HttpRequestBase httpMethod;
		// May generate IllegalArgumentException
		if (method.equals(POST)) {
			httpMethod = new HttpPost(urlStr);
		} else if (method.equals(PUT)) {
			httpMethod = new HttpPut(urlStr);
		} else if (method.equals(HEAD)) {
			httpMethod = new HttpHead(urlStr);
		} else if (method.equals(TRACE)) {
			httpMethod = new HttpTrace(urlStr);
		} else if (method.equals(OPTIONS)) {
			httpMethod = new HttpOptions(urlStr);
		} else if (method.equals(DELETE)) {
			httpMethod = new HttpDelete(urlStr);
		} else if (method.equals(GET)) {
			httpMethod = new HttpGet(urlStr);
		} else {
			log.error("Unexpected method (converted to GET): " + method);
			httpMethod = new HttpGet(urlStr);
		}
		return httpMethod;
	}

	/**
	 * Set up the PUT/POST data.
	 */
	private String sendData(HttpEntityEnclosingRequestBase method) throws IOException {
    	String contentEncoding = getContentEncoding();
		if(StringUtils.isEmpty(contentEncoding)){
			method.setEntity(new StringEntity(getRequestBody()));
		}else{
			method.setEntity(new StringEntity(getRequestBody(),contentEncoding));
		}
		return getRequestBody();
    }
	
    /**
     * Gets the ResponseHeaders
     *
     * @param response
     *            containing the headers
     * @return string containing the headers, one per line
     */
    private String getResponseHeaders(HttpResponse response) {
        StringBuilder headerBuf = new StringBuilder();
        org.apache.http.Header[] allHeaders = response.getAllHeaders();
        headerBuf.append(response.getStatusLine());// header[0] is not the status line...
        headerBuf.append("\n"); // $NON-NLS-1$

        for (int i = 0; i < allHeaders.length; i++) {
            headerBuf.append(allHeaders[i].getName());
            headerBuf.append(": "); // $NON-NLS-1$
            headerBuf.append(allHeaders[i].getValue());
            headerBuf.append("\n"); // $NON-NLS-1$
        }
        return headerBuf.toString();
    }
    
    /**
     * From the <code>HttpMethod</code>, store all the "set-cookie" key-pair
     * values in the cookieManager of the <code>UrlConfig</code>.
     *
     * @param response
     *            <code>HttpResponse</code> which represents the request
     * @param u
     *            <code>URL</code> of the URL request
     * @param cookieManager
     *            the <code>CookieManager</code> containing all the cookies
     */
    private void saveConnectionCookies(HttpResponse response, URL u, CookieManager cookieManager) {
        if (cookieManager != null) {
            org.apache.http.Header[] hdr = response.getHeaders(HEADER_SET_COOKIE);
            for (int i = 0; i < hdr.length; i++) {
                cookieManager.addCookieFromHeader(hdr[i].getValue(),u);
            }
        }
    }
    
    /**
     * HttpResponse => HttpMEthod
     * 
     * @author okubo
     *
     */
    private static class ConvertedHttpMethod extends GetMethod{
    	
    	
    	private HttpResponse httpResponse;
    	private URI uri;

		/**
		 * @param httpResponse
		 * @param uri
		 */
		public ConvertedHttpMethod(HttpResponse httpResponse ,URI uri) {
			super();
			this.httpResponse = httpResponse;
			this.uri = uri;
		}

		/**
		 * @see org.apache.commons.httpclient.HttpMethodBase#getResponseHeader(java.lang.String)
		 */
		@Override
		public org.apache.commons.httpclient.Header getResponseHeader(
				String headerName) {
			org.apache.http.Header header = httpResponse.getFirstHeader(headerName);
			return new org.apache.commons.httpclient.Header(header.getName(),header.getValue()); 
		}

		/**
		 * @see org.apache.commons.httpclient.HttpMethodBase#getURI()
		 */
		@Override
		public URI getURI() throws URIException {
			return uri;
		}
    }

}
