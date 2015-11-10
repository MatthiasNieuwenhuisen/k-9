
package com.fsck.k9.activity.setup;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DialogFragment;
import android.app.FragmentTransaction;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.provider.ContactsContract;
import android.util.Log;
import android.util.Xml;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.fsck.k9.Account;
import com.fsck.k9.K9;
import com.fsck.k9.Preferences;
import com.fsck.k9.R;
import com.fsck.k9.activity.K9Activity;
import com.fsck.k9.controller.MessagingController;
import com.fsck.k9.fragment.ConfirmationDialogFragment;
import com.fsck.k9.fragment.ConfirmationDialogFragment.ConfirmationDialogFragmentListener;
import com.fsck.k9.mail.AuthenticationFailedException;
import com.fsck.k9.mail.CertificateValidationException;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.Store;
import com.fsck.k9.mail.Transport;
import com.fsck.k9.mail.filter.Hex;
import com.fsck.k9.mail.store.webdav.WebDavStore;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Checks the given settings to make sure that they can be used to send and
 * receive mail.
 * 
 * XXX NOTE: The manifest for this app has it ignore config changes, because
 * it doesn't correctly deal with restarting while its thread is running.
 */
public class AccountSetupAutoconfig extends K9Activity implements OnClickListener,
        ConfirmationDialogFragmentListener{

    public static final int ACTIVITY_REQUEST_CODE = 1;

    private static final String EXTRA_EMAIL = "email";
    private static final String EXTRA_SERVER = "server";

    private static final String AUTOCONFIG_URL_PREFIX = "http://autoconfig.";
    private static final String AUTOCONFIG_URL_SUFFIX = "/mail/config-v1.1.xml?emailaddress=";

    private static final String AUTOCONFIG_FALLBACK_URL_PREFIX = "http://";
    private static final String AUTOCONFIG_FALLBACK_URL_SUFFIX = "/.well-known/autoconfig/mail/config-v1.1.xml";

    private Handler mHandler = new Handler();

    private ProgressBar mProgressBar;

    private TextView mMessageView;

    private boolean mCanceled;

    private boolean mDestroyed;

    private String mEmail;
    private String mServer;

    public static void actionAutoconfig(Activity context, String email, String server) {
        Intent i = new Intent(context, AccountSetupAutoconfig.class);
        i.putExtra(EXTRA_SERVER, server);
        i.putExtra(EXTRA_EMAIL, email);
        //i.putExtra(EXTRA_CHECK_DIRECTION, direction);
        context.startActivityForResult(i, ACTIVITY_REQUEST_CODE);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.account_setup_autoconfig);
        mMessageView = (TextView)findViewById(R.id.message);
        mProgressBar = (ProgressBar)findViewById(R.id.progress);
        findViewById(R.id.cancel).setOnClickListener(this);

        setMessage(R.string.account_setup_check_settings_retr_info_msg);
        mProgressBar.setIndeterminate(true);

        EmailTuple email = new EmailTuple();
        email.email = getIntent().getStringExtra(EXTRA_EMAIL);
        email.server = getIntent().getStringExtra(EXTRA_SERVER);

        new RetrieveAutoconfigTask().execute(email);
    }

    /*
    private void handleCertificateValidationException(CertificateValidationException cve) {
        Log.e(K9.LOG_TAG, "Error while testing settings", cve);

        X509Certificate[] chain = cve.getCertChain();
        // Avoid NullPointerException in acceptKeyDialog()
        if (chain != null) {
            acceptKeyDialog(
                    R.string.account_setup_failed_dlg_certificate_message_fmt,
                    cve);
        } else {
            showErrorDialog(
                    R.string.account_setup_failed_dlg_server_message_fmt,
                    errorMessageForCertificateException(cve));
        }
    }
    */

    @Override
    public void onDestroy() {
        super.onDestroy();
        mDestroyed = true;
        mCanceled = true;
    }

    private void setMessage(final int resId) {
        mMessageView.setText(getString(resId));
    }

    @Override
    public void onActivityResult(int reqCode, int resCode, Intent data) {
        setResult(resCode);
        finish();
    }

    private void onCancel() {
        mCanceled = true;
        //setMessage(R.string.account_setup_check_settings_canceling_msg);
    }

    public void onClick(View v) {
        switch (v.getId()) {
        case R.id.cancel:
            onCancel();
            break;
        }
    }

    private void showErrorDialog(final int msgResId, final Object... args) {
        mHandler.post(new Runnable() {
            public void run() {
                showDialogFragment(R.id.dialog_account_setup_error, getString(msgResId, args));
            }
        });
    }

    private void showDialogFragment(int dialogId, String customMessage) {
        if (mDestroyed) {
            return;
        }
        mProgressBar.setIndeterminate(false);

        DialogFragment fragment;
        switch (dialogId) {
            case R.id.dialog_account_setup_error: {
                fragment = ConfirmationDialogFragment.newInstance(dialogId,
                        getString(R.string.account_setup_failed_dlg_title),
                        customMessage,
                        getString(R.string.account_setup_failed_dlg_edit_details_action),
                        getString(R.string.account_setup_failed_dlg_continue_action)
                );
                break;
            }
            default: {
                throw new RuntimeException("Called showDialog(int) with unknown dialog id.");
            }
        }

        FragmentTransaction ta = getFragmentManager().beginTransaction();
        ta.add(fragment, getDialogTag(dialogId));
        ta.commitAllowingStateLoss();

        // TODO: commitAllowingStateLoss() is used to prevent https://code.google.com/p/android/issues/detail?id=23761
        // but is a bad...
        //fragment.show(ta, getDialogTag(dialogId));
    }

    private String getDialogTag(int dialogId) {
        return String.format(Locale.US, "dialog-%d", dialogId);
    }

    @Override
    public void doPositiveClick(int dialogId) {
        switch (dialogId) {
            case R.id.dialog_account_setup_error: {
                finish();
                break;
            }
        }
    }

    @Override
    public void doNegativeClick(int dialogId) {
        switch (dialogId) {
            case R.id.dialog_account_setup_error: {
                mCanceled = false;
                setResult(RESULT_OK);
                finish();
                break;
            }
        }
    }

    @Override
    public void dialogCancelled(int dialogId) {
        // nothing to do here...
    }

    private class EmailTuple {
        public String email;
        public String server;
    }
    /**
     * FIXME: Don't use an AsyncTask to perform network operations.
     * See also discussion in https://github.com/k9mail/k-9/pull/560
     */
    private class RetrieveAutoconfigTask extends AsyncTask<EmailTuple, Integer, AccountSetupBasics.Provider> {
        @Override
        protected AccountSetupBasics.Provider doInBackground(EmailTuple... params) {
            final String email = params[0].email;
            final String server = params[0].server;
            try {
                /*
                 * This task could be interrupted at any point, but network operations can block,
                 * so relying on InterruptedException is not enough. Instead, check after
                 * each potentially long-running operation.
                 */
                if (cancelled()) {
                    return null;
                }

                //clearCertificateErrorNotifications(direction);

                AccountSetupBasics.Provider provider = fetchConfig(email, server);

                if (cancelled()) {
                    return null;
                }

                setResult(RESULT_OK);
                finish();
                return provider;
            } catch (Throwable t) {
                Log.e(K9.LOG_TAG, "Error while fetching autoconfig settings", t);
                showErrorDialog(
                        R.string.account_setup_failed_dlg_server_message_fmt,
                        (t.getMessage() == null ? "" : t.getMessage()));
            }
            return null;
        }

        /*
        private void clearCertificateErrorNotifications(CheckDirection direction) {
            final MessagingController ctrl = MessagingController.getInstance(getApplication());
            ctrl.clearCertificateErrorNotifications(account, direction);
        }
        */

        private boolean cancelled() {
            if (mDestroyed) {
                return true;
            }
            if (mCanceled) {
                finish();
                return true;
            }
            return false;
        }

        private AccountSetupBasics.Provider fetchConfig( String email, String server ) {
            AccountSetupBasics.Provider provider = null;


            String url = AUTOCONFIG_URL_PREFIX + server + AUTOCONFIG_URL_SUFFIX + email;
            String fallback_url = AUTOCONFIG_FALLBACK_URL_PREFIX + server + AUTOCONFIG_FALLBACK_URL_SUFFIX;

            AutoconfigXmlParser parser = new AutoconfigXmlParser();
            try {
                parser.parse(downloadUrl(url));
            } catch ( IOException e ) {
                Log.e( K9.LOG_TAG, "Error: " + e.getMessage() );
            } catch ( XmlPullParserException e ) {
                Log.e( K9.LOG_TAG, "Error: " + e.getMessage() );
            }

            return provider;
        }

        // Given a string representation of a URL, sets up a connection and gets
        // an input stream.
        private InputStream downloadUrl(String urlString) throws IOException {
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setReadTimeout(10000 /* milliseconds */);
            conn.setConnectTimeout(15000 /* milliseconds */);
            conn.setRequestMethod("GET");
            conn.setDoInput(true);
            // Starts the query
            conn.connect();
            return conn.getInputStream();
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            setMessage(values[0]);
        }
    }

    public class AutoconfigXmlParser {
        // We don't use namespaces
        private final String ns = null;

        public List parse(InputStream in) throws XmlPullParserException, IOException {
            try {
                XmlPullParser parser = Xml.newPullParser();
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
                parser.setInput(in, null);
                parser.nextTag();
                return readFeed(parser);
            } finally {
                in.close();
            }
        }

        private List readFeed(XmlPullParser parser) throws XmlPullParserException, IOException {
            List entries = new ArrayList();

            parser.require(XmlPullParser.START_TAG, ns, "clientConfig");
            while (parser.next() != XmlPullParser.END_TAG) {
                if (parser.getEventType() != XmlPullParser.START_TAG) {
                    continue;
                }
                String name = parser.getName();
                // Starts by looking for the entry tag
                Log.e( K9.LOG_TAG, name );
                if (name.equals("emailProvider")) {
                    entries.add(readEntry(parser));
                } else {
                    skip(parser);
                }
            }
            return entries;
        }

        // Parses the contents of an entry. If it encounters a title, summary, or link tag, hands them off
        // to their respective "read" methods for processing. Otherwise, skips the tag.
        private AccountSetupBasics.Provider readEntry(XmlPullParser parser) throws XmlPullParserException, IOException {
            AccountSetupBasics.Provider provider = new AccountSetupBasics.Provider();
            Server incomingServer = null;
            Server outgoingServer = null;
            parser.require(XmlPullParser.START_TAG, ns, "clientConfig");
            while (parser.next() != XmlPullParser.END_TAG) {
                if (parser.getEventType() != XmlPullParser.START_TAG) {
                    continue;
                }
                String name = parser.getName();
                if (name.equals("incomingServer")) {
                    incomingServer = readServer(parser, "incomingServer");
                } else if (name.equals("outgoingServer")) {
                    outgoingServer = readServer(parser, "outgoingServer");
                } else {
                    skip(parser);
                }
            }
            Log.e( K9.LOG_TAG, incomingServer.type + " " + incomingServer.username );
            Log.e( K9.LOG_TAG, outgoingServer.type + " " + outgoingServer.username );
            return provider;
        }

        class Server {
            public String type;
            public String hostname;
            public String port;
            public String socketType;
            public String authentication;
            public String username;
        }

        private Server readServer(XmlPullParser parser, String serverTag) throws XmlPullParserException, IOException {
            Server server = new Server();
            parser.require(XmlPullParser.START_TAG, ns, serverTag);

            server.type = parser.getAttributeValue(null, "type");

            while (parser.next() != XmlPullParser.END_TAG) {
                if (parser.getEventType() != XmlPullParser.START_TAG) {
                    continue;
                }
                String name = parser.getName();
                if (name.equals("hostname")) {
                    server.hostname = readTag(parser, "hostname");
                } else if (name.equals("port")) {
                    server.port = readTag(parser, "port");
                } else if (name.equals("socketType")) {
                    server.socketType = readTag(parser, "socketType");
                } else if( name.equals("authentication") ) {
                    server.authentication = readTag(parser, "authentication");
                } else if( name.equals("username") ) {
                    server.username = readTag(parser, "username");
                } else {
                    skip(parser);
                }
            }
            return server;
        }

        private String readTag(XmlPullParser parser, String tag) throws IOException, XmlPullParserException {
            parser.require(XmlPullParser.START_TAG, ns, tag);
            String text = readText(parser);
            parser.require(XmlPullParser.END_TAG, ns, tag);
            return text;
        }

        private void skip(XmlPullParser parser) throws XmlPullParserException, IOException {
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                throw new IllegalStateException();
            }
            int depth = 1;
            while (depth != 0) {
                switch (parser.next()) {
                    case XmlPullParser.END_TAG:
                        depth--;
                        break;
                    case XmlPullParser.START_TAG:
                        depth++;
                        break;
                }
            }
        }

        // For the tags title and summary, extracts their text values.
        private String readText(XmlPullParser parser) throws IOException, XmlPullParserException {
            String result = "";
            if (parser.next() == XmlPullParser.TEXT) {
                result = parser.getText();
                parser.nextTag();
            }
            return result;
        }
    }
}
