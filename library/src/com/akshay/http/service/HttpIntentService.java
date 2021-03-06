package com.akshay.http.service;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;

import org.apache.commons.io.IOUtils;

import com.akshay.http.service.builders.ServiceIntentBuilder;
import com.akshay.http.service.constants.HttpStatusCodes;
import com.akshay.http.service.utils.ServiceUtilities.NetworkAvailabilityCallback;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.text.TextUtils;
import android.util.Log;

public class HttpIntentService extends IntentService {

	public static final String HTTP_SERVICE_TAG = "HttpIntentService";

	public static final int SERVICE_TYPE_GET = 0;
	public static final int SERVICE_TYPE_POST = 1;

	private static final String HTTP_POST = "POST";
	private static final String HTTP_GET = "GET";

	private static final int CONN_TIMEOUT = Settings.CONN_TIMEOUT;
	private static final int READ_TIMEOUT = Settings.READ_TIMEOUT;

	public static final String SERVICE_RESPONSE = "service_response";
	private static final String SERVICE_RESPONSE_CODE = "service_response_code";

	protected static final String TEST_URL = "http://www.google.com";

	public HttpIntentService() {
		super(HTTP_SERVICE_TAG);
	}

	@Override
	public void onCreate() {
		super.onCreate();
		disableConnectionReuseIfNecessary();
		enableHttpResponseCache();
	}

	private Bundle doGetServiceCall(Uri uri, Bundle bundle, Intent intent)
			throws IOException {
		String param = intent
				.getStringExtra(ServiceIntentBuilder.SYNC_INTENT_EXTRA_PARAM);
		String sUrl = (TextUtils.isEmpty(param)) ? uri.toString() : uri
				.toString() + param;
		if (Settings.DEBUG_MODE) {
			Log.v(HTTP_SERVICE_TAG, "HttpIntentService URL:" + sUrl);
		}
		URL url = new URL(sUrl);
		HttpURLConnection urlConnection = getHttpUrlConnection(url);
		urlConnection.setRequestMethod(HTTP_GET);
		try {
			InputStream in = urlConnection.getInputStream();
			bundle = copyStreamToBundle(in, bundle);
			in.close();
			int responseCode = urlConnection.getResponseCode();
			if (Settings.DEBUG_MODE) {
				Log.v(HTTP_SERVICE_TAG, "HttpIntentService Response Code:"
						+ responseCode);
			}
			bundle.putInt(SERVICE_RESPONSE_CODE, responseCode);
			return bundle;
		} finally {
			if (Settings.DEBUG_MODE) {
				Log.v(HTTP_SERVICE_TAG, "Disconnecting stream");
			}
			urlConnection.disconnect();
		}
	}

	private Bundle doPostServiceCall(Uri uri, Bundle bundle, Intent intent)
			throws ProtocolException, IOException {
		String param = intent
				.getStringExtra(ServiceIntentBuilder.SYNC_INTENT_EXTRA_PARAM);
		URL url = new URL(uri.toString());
		if (Settings.DEBUG_MODE) {
			Log.v(HTTP_SERVICE_TAG,
					"HttpIntentService URL:" + url.toExternalForm());
		}
		HttpURLConnection urlConnection = getHttpUrlConnection(url);
		try {
			urlConnection.setDoOutput(true);
			urlConnection.setChunkedStreamingMode(0);
			urlConnection.setRequestMethod(HTTP_POST);
			OutputStreamWriter wr = new OutputStreamWriter(
					urlConnection.getOutputStream());
			wr.write(param);
			wr.flush();
			InputStream in = urlConnection.getInputStream();
			bundle = copyStreamToBundle(in, bundle);
			in.close();
			wr.close();
			int responseCode = urlConnection.getResponseCode();
			if (Settings.DEBUG_MODE) {
				Log.v(HTTP_SERVICE_TAG, "HttpIntentService Response Code:"
						+ responseCode);
			}
			bundle.putInt(SERVICE_RESPONSE_CODE, responseCode);
			return bundle;
		} finally {
			if (Settings.DEBUG_MODE) {
				Log.v(HTTP_SERVICE_TAG, "Disconnecting stream");
			}
			urlConnection.disconnect();
		}
	}

	private Bundle copyStreamToBundle(InputStream in, Bundle bundle)
			throws IOException {
		bundle.putByteArray(SERVICE_RESPONSE, IOUtils.toByteArray(in));
		return bundle;
	}

	private HttpURLConnection getHttpUrlConnection(URL url) throws IOException,
			ProtocolException {
		HttpURLConnection urlConnection = (HttpURLConnection) url
				.openConnection();
		urlConnection.setReadTimeout(READ_TIMEOUT);
		urlConnection.setConnectTimeout(CONN_TIMEOUT);
		urlConnection.setRequestProperty("Content-Type",
				"text/plain; charset=utf-8");
		urlConnection.setInstanceFollowRedirects(true);
		return urlConnection;
	}

	private void disableConnectionReuseIfNecessary() {
		// HTTP connection reuse which was buggy pre-froyo
		if (Integer.parseInt(Build.VERSION.SDK) < Build.VERSION_CODES.FROYO) {
			System.setProperty("http.keepAlive", "false");
		}
	}

	private void enableHttpResponseCache() {
		try {
			long httpCacheSize = 10 * 1024 * 1024; // 10 MiB
			File httpCacheDir = new File(getCacheDir(), "http");
			Class.forName("android.net.http.HttpResponseCache")
					.getMethod("install", File.class, long.class)
					.invoke(null, httpCacheDir, httpCacheSize);
		} catch (Exception httpResponseCacheNotAvailable) {
			// Reflection for <4.0
		}
	}

	@Override
    protected void onHandleIntent(final Intent intent) {
        final ResultReceiver receiver = intent.getParcelableExtra(ServiceIntentBuilder.SYNC_INTENT_EXTRA_RECEIVER);
        final int serviceType = intent.getIntExtra(ServiceIntentBuilder.SYNC_INTENT_EXTRA_SERVICE_TYPE, 0);
        final Uri uri = intent.getData();
        isNetworkAvailable(new NetworkAvailabilityCallback() {
            @Override
            public void isNetworkAvailable(boolean flag) {
                if (flag) {
                    try {
                        Bundle response = doServiceCall(uri, serviceType, new Bundle(), intent);
                        receiver.send(response.getInt(SERVICE_RESPONSE_CODE), response);
                    }catch (FileNotFoundException f){
                    	if (Settings.DEBUG_MODE){
                            f.printStackTrace();
                        }
                    	receiver.send(HttpStatusCodes.FORBIDDEN, new Bundle());	                        
                    } catch (Exception e) {
                        if (Settings.DEBUG_MODE){
                            e.printStackTrace();
                        	Log.e(HTTP_SERVICE_TAG, "Timout");
                        }
                        receiver.send(HttpStatusCodes.GATEWAY_TIMEOUT, null);
                    }
                } else {
                	if (Settings.DEBUG_MODE){
                    	Log.e(HTTP_SERVICE_TAG, "No connectivity");
                    }
                    receiver.send(HttpStatusCodes.GATEWAY_TIMEOUT, null);
                }
            }
        });
    }

	private Bundle doServiceCall(Uri uri, int serviceType, Bundle bundle,
			Intent intent) throws IOException {
		switch (serviceType) {
		case SERVICE_TYPE_GET:
			return doGetServiceCall(uri, bundle, intent);
		case SERVICE_TYPE_POST:
			return doPostServiceCall(uri, bundle, intent);
		default:
			return bundle;
		}
	}

	private void isNetworkAvailable(
			NetworkAvailabilityCallback networkAvailabilityCallback) {
		ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
		NetworkInfo activeNetworkInfo = connectivityManager
				.getActiveNetworkInfo();
		networkAvailabilityCallback
				.isNetworkAvailable((activeNetworkInfo != null));
	}

	public static class Settings {
		public static int CONN_TIMEOUT = 10000;
		public static int READ_TIMEOUT = 30000;
		public static boolean DEBUG_MODE = false;
	}

}