package eu.emundo.generator.octane;

import java.security.GeneralSecurityException;

import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.hpe.adm.nga.sdk.network.google.GoogleHttpClient;

public class GoogleHttpClientWrapper extends GoogleHttpClient {

	/**
	 * Client which accepts all certificates
	 *
	 * @param urlDomain
	 *            Octane URL
	 * @throws GeneralSecurityException
	 *             GeneralSecurityException
	 */
	public GoogleHttpClientWrapper(final String urlDomain) throws GeneralSecurityException {
		super(urlDomain);

		final HttpTransport httpTransport = new NetHttpTransport.Builder().doNotValidateCertificate().build();
		requestFactory = httpTransport.createRequestFactory(requestInitializer);
	}
}
