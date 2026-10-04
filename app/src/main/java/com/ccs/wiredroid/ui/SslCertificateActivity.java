package com.ccs.wiredroid.ui;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.AppPreferences;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.Colors;
import com.ccs.wiredroid.util.FullScreenHelper;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.DSAPublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.security.auth.x500.X500Principal;

/**
 * SSL certificate viewer: reads an X.509 chain either from a live TLS host or
 * from a local {@code .crt / .cer / .pem / .der} file and lays it out leaf → root.
 *
 * <p>Pure Android UI (no WebView, no XML layout), matching the other tool
 * screens in this app.</p>
 */
public class SslCertificateActivity extends AppCompatActivity {

    private static final String EXTRA_CERT_PATH = "cert_path";

    private static final int REQ_PICK_CERT = 0x55C7;
    private static final int DEFAULT_PORT = 443;
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 8000;
    private static final int EXPIRING_SOON_DAYS = 30;
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    private static final int MAX_FILE_BYTES = 4 * 1024 * 1024;

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private static final Pattern PEM_BLOCK = Pattern.compile(
            "-----BEGIN[^-]*-----(.*?)-----END[^-]*-----", Pattern.DOTALL);

    private AppPreferences prefs;
    private AppTheme theme;
    private Typeface mono;

    private EditText hostInput;
    private EditText portInput;
    private TextView statusText;
    private LinearLayout chainContainer;
    private ScrollView chainScroll;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    public static void launch(Context context) {
        context.startActivity(new Intent(context, SslCertificateActivity.class));
    }

    public static void launch(Context context, File certFile) {
        Intent i = new Intent(context, SslCertificateActivity.class);
        i.putExtra(EXTRA_CERT_PATH, certFile.getAbsolutePath());
        context.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = new AppPreferences(this);
        theme = AppTheme.byId(prefs.getThemeId(), prefs);
        setTheme(theme.dark ? R.style.Theme_WireDroid : R.style.Theme_WireDroid_Light);

        super.onCreate(savedInstanceState);
        try {
            setContentView(buildRoot());
        } catch (Exception e) {
            android.util.Log.e("SslCert", "buildRoot failed", e);
            LinearLayout fallback = new LinearLayout(this);
            fallback.setOrientation(LinearLayout.VERTICAL);
            fallback.setBackgroundColor(0xFFFFFFFF);
            TextView errTv = new TextView(this);
            errTv.setText(getString(R.string.ssl_status_error, String.valueOf(e.getMessage())));
            errTv.setTextColor(0xFFFF0000);
            errTv.setPadding(16, 16, 16, 16);
            fallback.addView(errTv);
            setContentView(fallback);
            return;
        }
        FullScreenHelper.enable(this);

        String path = getIntent().getStringExtra(EXTRA_CERT_PATH);
        if (path != null) {
            loadFile(new File(path));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    // ── Layout ──────────────────────────────────────────────────────────────

    private View buildRoot() {
        mono = prefs.resolveTypeface();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.bg);

        Toolbar toolbar = new Toolbar(this);
        toolbar.setBackgroundColor(theme.toolbar);
        toolbar.setTitle(R.string.ssl_title);
        toolbar.setTitleTextColor(theme.text);
        toolbar.setSubtitle(R.string.ssl_subtitle);
        toolbar.setSubtitleTextColor(theme.textDim);
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());
        root.addView(toolbar);

        // ── Host / port row ────────────────────────────────────────────────
        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setPadding(dp(8), dp(8), dp(8), dp(4));

        hostInput = new EditText(this);
        hostInput.setHint(R.string.ssl_hint_host);
        hostInput.setContentDescription(getString(R.string.ssl_a11y_host));
        hostInput.setHintTextColor(theme.textDim);
        hostInput.setTextColor(theme.text);
        hostInput.setTextSize(13);
        hostInput.setTypeface(mono);
        hostInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        hostInput.setSingleLine(true);
        hostInput.setBackground(roundedFill(theme.consoleBg, theme.separator));
        hostInput.setPadding(dp(10), dp(8), dp(10), dp(8));
        inputRow.addView(hostInput, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        portInput = new EditText(this);
        portInput.setHint(R.string.ssl_hint_port);
        portInput.setContentDescription(getString(R.string.ssl_a11y_port));
        portInput.setHintTextColor(theme.textDim);
        portInput.setTextColor(theme.text);
        portInput.setTextSize(13);
        portInput.setTypeface(mono);
        portInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        portInput.setSingleLine(true);
        portInput.setGravity(Gravity.CENTER);
        portInput.setBackground(roundedFill(theme.consoleBg, theme.separator));
        portInput.setPadding(dp(6), dp(8), dp(6), dp(8));
        LinearLayout.LayoutParams portLp =
                new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT);
        portLp.leftMargin = dp(6);
        inputRow.addView(portInput, portLp);

        root.addView(inputRow);

        // ── Action row ─────────────────────────────────────────────────────
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setPadding(dp(8), 0, dp(8), dp(4));

        TextView fetchBtn = createButton(getString(R.string.ssl_action_fetch), theme.successText);
        fetchBtn.setContentDescription(getString(R.string.ssl_a11y_fetch));
        fetchBtn.setOnClickListener(v -> fetchFromHost());
        actionRow.addView(fetchBtn);

        TextView openBtn = createButton(getString(R.string.ssl_action_open_file), theme.accent);
        openBtn.setContentDescription(getString(R.string.ssl_a11y_open_file));
        openBtn.setOnClickListener(v -> openFilePicker());
        actionRow.addView(openBtn);

        TextView clearBtn = createButton(getString(R.string.ssl_action_clear), theme.textDim);
        clearBtn.setContentDescription(getString(R.string.ssl_a11y_clear));
        clearBtn.setOnClickListener(v -> {
            chainContainer.removeAllViews();
            statusText.setText(R.string.ssl_status_idle);
        });
        actionRow.addView(clearBtn);

        root.addView(actionRow);

        // ── Inspection-mode notice ─────────────────────────────────────────
        TextView notice = new TextView(this);
        notice.setText(R.string.ssl_notice_inspection);
        notice.setTextColor(theme.textDim);
        notice.setTextSize(10);
        notice.setPadding(dp(12), dp(2), dp(12), dp(8));
        root.addView(notice);

        View topDivider = new View(this);
        topDivider.setBackgroundColor(theme.separator);
        root.addView(topDivider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));

        // ── Chain ──────────────────────────────────────────────────────────
        chainScroll = new ScrollView(this);
        chainScroll.setFillViewport(true);
        chainContainer = new LinearLayout(this);
        chainContainer.setOrientation(LinearLayout.VERTICAL);
        chainContainer.setPadding(dp(8), dp(8), dp(8), dp(16));
        chainScroll.addView(chainContainer);
        root.addView(chainScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ── Status bar ─────────────────────────────────────────────────────
        statusText = new TextView(this);
        statusText.setBackgroundColor(theme.statusBar);
        statusText.setTextColor(theme.textDim);
        statusText.setTextSize(10);
        statusText.setPadding(dp(8), dp(4), dp(8), dp(4));
        statusText.setText(R.string.ssl_status_idle);
        root.addView(statusText);

        return root;
    }

    // ── Source 1: live host ─────────────────────────────────────────────────

    private void fetchFromHost() {
        String raw = hostInput.getText().toString().trim();
        if (raw.isEmpty()) {
            Toast.makeText(this, R.string.ssl_error_empty_host, Toast.LENGTH_SHORT).show();
            return;
        }

        // Tolerate a pasted URL: https://example.com:8443/path → example.com + 8443
        int scheme = raw.indexOf("://");
        if (scheme >= 0) raw = raw.substring(scheme + 3);
        int slash = raw.indexOf('/');
        if (slash >= 0) raw = raw.substring(0, slash);

        int inlinePort = -1;
        int colon = raw.lastIndexOf(':');
        if (colon > 0 && raw.indexOf(':') == colon) { // single colon → not an IPv6 literal
            try {
                inlinePort = Integer.parseInt(raw.substring(colon + 1).trim());
                raw = raw.substring(0, colon);
            } catch (NumberFormatException ignored) {
                inlinePort = -1;
            }
        }

        String portRaw = portInput.getText().toString().trim();
        int port;
        if (!portRaw.isEmpty()) {
            try {
                port = Integer.parseInt(portRaw);
            } catch (NumberFormatException e) {
                port = -1;
            }
        } else {
            port = inlinePort > 0 ? inlinePort : DEFAULT_PORT;
        }
        if (port < 1 || port > 65535) {
            Toast.makeText(this, R.string.ssl_error_bad_port, Toast.LENGTH_SHORT).show();
            return;
        }

        final String host = raw;
        final int finalPort = port;
        hostInput.setText(host);
        portInput.setText(String.valueOf(finalPort));

        chainContainer.removeAllViews();
        statusText.setText(getString(R.string.ssl_status_connecting, host, finalPort));

        io.execute(() -> {
            long start = System.currentTimeMillis();
            Socket plain = null;
            SSLSocket ssl = null;
            try {
                // The socket is created by hand rather than through
                // SSLSocketFactory.createSocket(host, port) so that the TCP connect
                // itself honours a timeout; the factory variant blocks indefinitely.
                plain = new Socket();
                plain.connect(new InetSocketAddress(host, finalPort), CONNECT_TIMEOUT_MS);
                plain.setSoTimeout(READ_TIMEOUT_MS);

                SSLSocketFactory factory = inspectionOnlySslContext().getSocketFactory();
                ssl = (SSLSocket) factory.createSocket(plain, host, finalPort, true);
                ssl.setSoTimeout(READ_TIMEOUT_MS);

                // Offer everything this device supports — a viewer should be able to
                // look at a legacy endpoint, not just the ones it would talk to.
                try {
                    ssl.setEnabledProtocols(ssl.getSupportedProtocols());
                } catch (Exception ignored) { }

                // SNI: many hosts serve a default (wrong) certificate without it.
                try {
                    SSLParameters params = ssl.getSSLParameters();
                    if (!looksLikeIpLiteral(host)) {
                        List<SNIServerName> names =
                                Collections.singletonList(new SNIHostName(host));
                        params.setServerNames(names);
                    }
                    ssl.setSSLParameters(params);
                } catch (Exception ignored) { }

                ssl.startHandshake();

                Certificate[] peer = ssl.getSession().getPeerCertificates();
                Chain chain = new Chain();
                chain.peer = getString(R.string.ssl_status_source_host, host, finalPort);
                chain.protocol = ssl.getSession().getProtocol();
                chain.cipher = ssl.getSession().getCipherSuite();
                chain.certs = describeAll(toX509(peer));
                chain.elapsedMs = System.currentTimeMillis() - start;

                if (chain.certs.isEmpty()) {
                    postError(getString(R.string.ssl_error_no_certs));
                } else {
                    ui.post(() -> renderChain(chain));
                }
            } catch (Throwable t) {
                postError(messageOf(t));
            } finally {
                closeQuietly(ssl);
                if (ssl == null) closeQuietly(plain);
            }
        });
    }

    /**
     * An {@link SSLContext} that trusts every peer, for INSPECTION ONLY.
     *
     * <p>The whole point of this screen is to look at certificates that a normal
     * client would reject — expired, self-signed, wrong host, unknown CA. A
     * validating trust manager would throw during {@code startHandshake()} and the
     * user would never get to see the very certificate they came here to debug, so
     * validation is deliberately switched off and reported in the UI instead.</p>
     *
     * <p><b>Containment:</b> the context is built fresh on every fetch, is never
     * stored in a field or a static, is never handed to OkHttp/HttpsURLConnection,
     * and never touches {@link SSLContext#setDefault} or
     * {@code HttpsURLConnection.setDefaultSSLSocketFactory}. It cannot leak out of
     * this activity, and nothing outside this file can reach it. Do not lift this
     * method into a shared utility — every other network call in the app must keep
     * using the platform trust store.</p>
     */
    private static SSLContext inspectionOnlySslContext() throws Exception {
        // X509ExtendedTrustManager (API 24+) rather than the plain X509TrustManager so
        // Conscrypt does not wrap us in its own hostname-checking shim.
        TrustManager[] inspectOnly = new TrustManager[]{
                new X509ExtendedTrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        // Inspection only — intentionally no validation.
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        // Inspection only — intentionally no validation.
                    }

                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType,
                                                   Socket socket) {
                        // Inspection only — intentionally no validation.
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType,
                                                   Socket socket) {
                        // Inspection only — intentionally no validation.
                    }

                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType,
                                                   SSLEngine engine) {
                        // Inspection only — intentionally no validation.
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType,
                                                   SSLEngine engine) {
                        // Inspection only — intentionally no validation.
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, inspectOnly, new java.security.SecureRandom());
        return ctx;
    }

    private static boolean looksLikeIpLiteral(String host) {
        if (host.indexOf(':') >= 0) return true;                // IPv6
        return host.matches("\\d{1,3}(\\.\\d{1,3}){3}");        // IPv4
    }

    // ── Source 2: local file ────────────────────────────────────────────────

    private void openFilePicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        // Certificate files carry a zoo of mime types (and often none at all),
        // so filtering by type would hide the very file the user wants.
        i.setType("*/*");
        try {
            startActivityForResult(
                    Intent.createChooser(i, getString(R.string.ssl_picker_title)),
                    REQ_PICK_CERT);
        } catch (Exception e) {
            Toast.makeText(this, R.string.ssl_error_no_picker, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_CERT || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        loadUri(uri);
    }

    private void loadFile(File file) {
        if (file == null || !file.isFile()) {
            postError(getString(R.string.ssl_error_missing_file,
                    file == null ? "" : file.getName()));
            return;
        }
        String name = file.getName();
        chainContainer.removeAllViews();
        statusText.setText(getString(R.string.ssl_status_reading, name));
        io.execute(() -> {
            long start = System.currentTimeMillis();
            try {
                if (file.length() > MAX_FILE_BYTES) {
                    postError(getString(R.string.ssl_error_too_large, file.length() / 1024));
                    return;
                }
                byte[] data;
                try (InputStream in = new FileInputStream(file)) {
                    data = readAll(in);
                }
                finishFileLoad(data, name, start);
            } catch (Throwable t) {
                postError(getString(R.string.ssl_error_read_file, messageOf(t)));
            }
        });
    }

    private void loadUri(Uri uri) {
        String name = displayName(uri);
        chainContainer.removeAllViews();
        statusText.setText(getString(R.string.ssl_status_reading, name));
        io.execute(() -> {
            long start = System.currentTimeMillis();
            try {
                byte[] data;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) {
                        postError(getString(R.string.ssl_error_read_file, String.valueOf(uri)));
                        return;
                    }
                    data = readAll(in);
                }
                finishFileLoad(data, name, start);
            } catch (Throwable t) {
                postError(getString(R.string.ssl_error_read_file, messageOf(t)));
            }
        });
    }

    /** Shared tail of both file paths — runs on the io thread. */
    private void finishFileLoad(byte[] data, String name, long start) {
        List<X509Certificate> parsed;
        try {
            parsed = parseCertificates(data);
        } catch (Throwable t) {
            postError(getString(R.string.ssl_error_parse, messageOf(t)));
            return;
        }
        if (parsed.isEmpty()) {
            postError(getString(R.string.ssl_error_no_certs));
            return;
        }
        Chain chain = new Chain();
        chain.peer = getString(R.string.ssl_status_source_file, name);
        chain.certs = describeAll(parsed);
        chain.elapsedMs = System.currentTimeMillis() - start;
        ui.post(() -> renderChain(chain));
    }

    /**
     * Parses DER, a single PEM, or a PEM bundle holding several certificates.
     * {@code generateCertificates} handles both encodings on its own; the manual
     * PEM pass is the fallback for files with leading prose, odd armour labels or
     * a stray blob the factory refuses to look past.
     */
    private static List<X509Certificate> parseCertificates(byte[] data) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        List<X509Certificate> out = new ArrayList<>();

        try {
            Collection<? extends Certificate> certs =
                    cf.generateCertificates(new ByteArrayInputStream(data));
            for (Certificate c : certs) {
                if (c instanceof X509Certificate) out.add((X509Certificate) c);
            }
        } catch (Exception ignored) {
            // fall through to the manual passes
        }
        if (!out.isEmpty()) return out;

        // Manual PEM: pull each base64 block out ourselves.
        String text = new String(data, "ISO-8859-1");
        Matcher m = PEM_BLOCK.matcher(text);
        while (m.find()) {
            try {
                byte[] der = Base64.decode(m.group(1), Base64.DEFAULT);
                Certificate c = cf.generateCertificate(new ByteArrayInputStream(der));
                if (c instanceof X509Certificate) out.add((X509Certificate) c);
            } catch (Exception ignored) {
                // skip an unreadable block, keep the rest of the bundle
            }
        }
        if (!out.isEmpty()) return out;

        // Last resort: a single raw DER body. Let this one throw — its message is
        // the most useful thing we can show when nothing at all parsed.
        Certificate c = cf.generateCertificate(new ByteArrayInputStream(data));
        if (c instanceof X509Certificate) out.add((X509Certificate) c);
        return out;
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        // InputStream.readAllBytes() is API 33; minSdk here is 26.
        ByteArrayOutputStream buf = new ByteArrayOutputStream(8192);
        byte[] chunk = new byte[8192];
        int n;
        int total = 0;
        while ((n = in.read(chunk)) > 0) {
            total += n;
            if (total > MAX_FILE_BYTES) break;
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0 && !c.isNull(idx)) return c.getString(idx);
            }
        } catch (Exception ignored) { }
        String last = uri.getLastPathSegment();
        return last != null ? last : uri.toString();
    }

    // ── Certificate description (off the main thread) ───────────────────────

    private List<CertInfo> describeAll(List<X509Certificate> certs) {
        List<CertInfo> out = new ArrayList<>(certs.size());
        for (int i = 0; i < certs.size(); i++) {
            X509Certificate next = i + 1 < certs.size() ? certs.get(i + 1) : null;
            out.add(describe(certs.get(i), i, certs.size(), next));
        }
        return out;
    }

    private CertInfo describe(X509Certificate cert, int index, int total, X509Certificate next) {
        CertInfo info = new CertInfo();
        info.index = index;

        String subjectDn = cert.getSubjectX500Principal().getName(X500Principal.RFC2253);
        String issuerDn = cert.getIssuerX500Principal().getName(X500Principal.RFC2253);
        info.subject = prettyDn(subjectDn);
        info.issuer = prettyDn(issuerDn);
        info.commonName = firstCn(subjectDn);
        if (info.commonName == null) info.commonName = getString(R.string.ssl_chain_unnamed);

        info.selfSigned = cert.getSubjectX500Principal().equals(cert.getIssuerX500Principal());

        if (total == 1) {
            info.role = getString(R.string.ssl_depth_leaf);
        } else if (index == 0) {
            info.role = getString(R.string.ssl_depth_leaf);
        } else if (index == total - 1 && info.selfSigned) {
            info.role = getString(R.string.ssl_depth_root);
        } else {
            info.role = getString(R.string.ssl_depth_intermediate);
        }

        // A broken chain is the usual reason anyone opens this screen: the issuer of
        // this certificate must be the subject of the next one down the list.
        info.chainBreak = next != null
                && !cert.getIssuerX500Principal().equals(next.getSubjectX500Principal());

        // ── Validity ───────────────────────────────────────────────────────
        Date notBefore = cert.getNotBefore();
        Date notAfter = cert.getNotAfter();
        SimpleDateFormat fmt = new SimpleDateFormat(getString(R.string.ssl_date_format), Locale.US);
        info.notBefore = fmt.format(notBefore);
        info.notAfter = fmt.format(notAfter);

        long now = System.currentTimeMillis();
        info.daysRemaining = (notAfter.getTime() - now) / DAY_MS;
        if (notBefore.getTime() > now) {
            long daysUntil = (notBefore.getTime() - now) / DAY_MS + 1;
            info.validityState = CertInfo.NOT_YET_VALID;
            info.validitySummary = getString(R.string.ssl_value_not_yet_valid, daysUntil);
        } else if (notAfter.getTime() < now) {
            long ago = (now - notAfter.getTime()) / DAY_MS;
            info.validityState = CertInfo.EXPIRED;
            info.validitySummary = getString(R.string.ssl_value_expired, ago);
        } else if (info.daysRemaining < 1) {
            info.validityState = CertInfo.EXPIRING;
            info.validitySummary = getString(R.string.ssl_value_expires_today);
        } else if (info.daysRemaining < EXPIRING_SOON_DAYS) {
            info.validityState = CertInfo.EXPIRING;
            info.validitySummary = getString(R.string.ssl_value_expiring_soon, info.daysRemaining);
        } else {
            info.validityState = CertInfo.VALID;
            info.validitySummary = getString(R.string.ssl_value_valid, info.daysRemaining);
        }

        // ── Details ────────────────────────────────────────────────────────
        info.serial = hexPairs(cert.getSerialNumber().toByteArray());
        info.signatureAlgorithm = cert.getSigAlgName();
        info.version = getString(R.string.ssl_value_version, cert.getVersion());

        PublicKey key = cert.getPublicKey();
        String keyAlg = key != null ? key.getAlgorithm() : null;
        int bits = 0;
        if (key instanceof RSAPublicKey) {
            bits = ((RSAPublicKey) key).getModulus().bitLength();
        } else if (key instanceof ECPublicKey) {
            ECPublicKey ec = (ECPublicKey) key;
            if (ec.getParams() != null && ec.getParams().getCurve() != null) {
                bits = ec.getParams().getCurve().getField().getFieldSize();
            }
        } else if (key instanceof DSAPublicKey) {
            DSAPublicKey dsa = (DSAPublicKey) key;
            if (dsa.getParams() != null) bits = dsa.getParams().getP().bitLength();
        }
        if (keyAlg == null) {
            info.publicKey = getString(R.string.ssl_value_none);
        } else if (bits > 0) {
            info.publicKey = getString(R.string.ssl_value_key_sized, keyAlg, bits);
        } else {
            info.publicKey = keyAlg;
        }

        // ── Subject Alternative Names ──────────────────────────────────────
        String[] sanTypes = getResources().getStringArray(R.array.ssl_san_types);
        try {
            Collection<List<?>> sans = cert.getSubjectAlternativeNames();
            if (sans != null) {
                for (List<?> entry : sans) {
                    if (entry == null || entry.size() < 2) continue;
                    Object rawType = entry.get(0);
                    int type = rawType instanceof Integer ? (Integer) rawType : -1;
                    Object rawValue = entry.get(1);
                    String value;
                    if (rawValue instanceof byte[]) {
                        value = hexPairs((byte[]) rawValue);
                    } else {
                        value = String.valueOf(rawValue);
                    }
                    String label = (type >= 0 && type < sanTypes.length)
                            ? sanTypes[type] : String.valueOf(type);
                    info.subjectAltNames.add(new String[]{label, value});
                }
            }
        } catch (Exception ignored) { }

        // ── Basic constraints / key usage ──────────────────────────────────
        int basic = cert.getBasicConstraints();
        if (basic < 0) {
            info.basicConstraints = getString(R.string.ssl_value_ca_no);
            info.isCa = false;
        } else {
            info.isCa = true;
            if (basic == Integer.MAX_VALUE) {
                info.basicConstraints = getString(R.string.ssl_value_path_len_unlimited);
            } else {
                info.basicConstraints = getString(R.string.ssl_value_path_len, basic);
            }
        }

        String[] usageNames = getResources().getStringArray(R.array.ssl_key_usage_bits);
        boolean[] usage = cert.getKeyUsage();
        if (usage != null) {
            for (int i = 0; i < usage.length && i < usageNames.length; i++) {
                if (usage[i]) info.keyUsage.add(usageNames[i]);
            }
        }
        try {
            List<String> ekus = cert.getExtendedKeyUsage();
            if (ekus != null) {
                for (String oid : ekus) info.extKeyUsage.add(ekuLabel(oid));
            }
        } catch (Exception ignored) { }

        // ── Fingerprints + PEM ─────────────────────────────────────────────
        try {
            byte[] der = cert.getEncoded();
            info.sha256 = digest(der, "SHA-256");
            info.sha1 = digest(der, "SHA-1");
            info.pem = toPem(der);
        } catch (Exception e) {
            info.sha256 = getString(R.string.ssl_value_none);
            info.sha1 = getString(R.string.ssl_value_none);
        }

        return info;
    }

    private String ekuLabel(String oid) {
        if (oid == null) return getString(R.string.ssl_value_none);
        switch (oid) {
            case "2.5.29.37.0":         return getString(R.string.ssl_eku_any);
            case "1.3.6.1.5.5.7.3.1":   return getString(R.string.ssl_eku_server_auth);
            case "1.3.6.1.5.5.7.3.2":   return getString(R.string.ssl_eku_client_auth);
            case "1.3.6.1.5.5.7.3.3":   return getString(R.string.ssl_eku_code_signing);
            case "1.3.6.1.5.5.7.3.4":   return getString(R.string.ssl_eku_email);
            case "1.3.6.1.5.5.7.3.8":   return getString(R.string.ssl_eku_timestamp);
            case "1.3.6.1.5.5.7.3.9":   return getString(R.string.ssl_eku_ocsp);
            default:                    return oid;
        }
    }

    // ── DN handling ─────────────────────────────────────────────────────────

    /** Splits an RFC 2253 DN into label/value pairs, one per RDN. */
    private List<String[]> prettyDn(String dn) {
        List<String[]> out = new ArrayList<>();
        if (dn == null || dn.isEmpty()) return out;
        for (String rdn : splitDn(dn)) {
            int eq = rdn.indexOf('=');
            if (eq <= 0) {
                out.add(new String[]{"", unescapeDn(rdn)});
                continue;
            }
            String type = rdn.substring(0, eq).trim();
            String value = unescapeDn(rdn.substring(eq + 1).trim());
            out.add(new String[]{dnLabel(type), value});
        }
        return out;
    }

    /** Splits on commas that are neither escaped nor inside quotes. */
    private static List<String> splitDn(String dn) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean escaped = false;
        boolean quoted = false;
        for (int i = 0; i < dn.length(); i++) {
            char c = dn.charAt(i);
            if (escaped) {
                cur.append('\\').append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                quoted = !quoted;
            } else if ((c == ',' || c == ';') && !quoted) {
                if (cur.length() > 0) out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString().trim());
        return out;
    }

    private static String unescapeDn(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char n = value.charAt(++i);
                // \XX hex escape (RFC 2253) — decode when both digits are hex.
                if (i + 1 < value.length() && isHex(n) && isHex(value.charAt(i + 1))) {
                    sb.append((char) Integer.parseInt(value.substring(i, i + 2), 16));
                    i++;
                } else {
                    sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private String dnLabel(String type) {
        switch (type.toUpperCase(Locale.US)) {
            case "CN":                  return getString(R.string.ssl_rdn_cn);
            case "O":                   return getString(R.string.ssl_rdn_o);
            case "OU":                  return getString(R.string.ssl_rdn_ou);
            case "C":                   return getString(R.string.ssl_rdn_c);
            case "ST":                  return getString(R.string.ssl_rdn_st);
            case "L":                   return getString(R.string.ssl_rdn_l);
            case "STREET":              return getString(R.string.ssl_rdn_street);
            case "DC":                  return getString(R.string.ssl_rdn_dc);
            case "UID":                 return getString(R.string.ssl_rdn_uid);
            case "T":                   return getString(R.string.ssl_rdn_title);
            case "SERIALNUMBER":
            case "2.5.4.5":             return getString(R.string.ssl_rdn_serial);
            case "EMAILADDRESS":
            case "1.2.840.113549.1.9.1": return getString(R.string.ssl_rdn_email);
            default:                    return type;
        }
    }

    private static String firstCn(String dn) {
        for (String rdn : splitDn(dn)) {
            int eq = rdn.indexOf('=');
            if (eq > 0 && "CN".equalsIgnoreCase(rdn.substring(0, eq).trim())) {
                return unescapeDn(rdn.substring(eq + 1).trim());
            }
        }
        return null;
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    private void renderChain(Chain chain) {
        chainContainer.removeAllViews();

        // Connection / source summary
        LinearLayout summary = new LinearLayout(this);
        summary.setOrientation(LinearLayout.VERTICAL);
        summary.setBackground(roundedFill(
                Colors.blend(theme.consoleBg, theme.bg, 0.35f), theme.separator));
        summary.setPadding(dp(10), dp(8), dp(10), dp(8));

        sectionTitle(summary, getString(R.string.ssl_section_connection));
        field(summary, getString(R.string.ssl_label_peer), chain.peer, theme.text, true);
        if (chain.protocol != null) {
            field(summary, getString(R.string.ssl_label_protocol), chain.protocol, theme.text, true);
        }
        if (chain.cipher != null) {
            field(summary, getString(R.string.ssl_label_cipher), chain.cipher, theme.text, true);
        }
        field(summary, getString(R.string.ssl_label_handshake),
                getString(R.string.ssl_status_elapsed, chain.elapsedMs), theme.textDim, true);
        chainContainer.addView(summary);

        for (CertInfo info : chain.certs) {
            chainContainer.addView(buildCard(info, chain.certs.size()));
        }

        statusText.setText(getResources().getQuantityString(
                R.plurals.ssl_status_chain, chain.certs.size(), chain.certs.size()));
        chainScroll.post(() -> chainScroll.scrollTo(0, 0));
    }

    private View buildCard(CertInfo info, int total) {
        // depth indicator: each step down the chain is indented and gets its own rail
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(8);
        rowLp.leftMargin = dp(10) * Math.min(info.index, 6);
        row.setLayoutParams(rowLp);

        float depthMix = total <= 1 ? 0f : Math.min(1f, info.index / (float) (total - 1));
        int railColor = Colors.blend(theme.accent, theme.textDim, depthMix * 0.7f);

        View rail = new View(this);
        rail.setBackground(roundedSolid(railColor));
        row.addView(rail, new LinearLayout.LayoutParams(dp(3), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundedFill(
                Colors.blend(theme.consoleBg, theme.bg, 0.25f), theme.separator));
        card.setPadding(dp(10), dp(8), dp(10), dp(10));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cardLp.leftMargin = dp(6);
        row.addView(card, cardLp);

        // ── Header: depth badge, role, copy PEM ────────────────────────────
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView badge = new TextView(this);
        badge.setText(getString(R.string.ssl_depth_badge, info.index));
        badge.setTextColor(theme.dark ? theme.bg : 0xFFFFFFFF);
        badge.setTextSize(10);
        badge.setTypeface(mono, Typeface.BOLD);
        badge.setBackground(roundedSolid(railColor));
        badge.setPadding(dp(6), dp(2), dp(6), dp(2));
        header.addView(badge);

        TextView role = new TextView(this);
        role.setText(info.role);
        role.setTextColor(theme.textDim);
        role.setTextSize(10);
        role.setAllCaps(true);
        role.setPadding(dp(6), 0, dp(6), 0);
        header.addView(role, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView copyPem = createButton(getString(R.string.ssl_action_copy_pem), theme.accent);
        copyPem.setContentDescription(getString(R.string.ssl_a11y_copy_pem));
        copyPem.setTextSize(11);
        copyPem.setPadding(dp(8), dp(4), dp(8), dp(4));
        copyPem.setOnClickListener(v -> copyPem(info));
        header.addView(copyPem);

        card.addView(header);

        // ── Common name + validity pill ────────────────────────────────────
        TextView title = new TextView(this);
        title.setText(info.commonName);
        title.setTextColor(theme.text);
        title.setTextSize(14);
        title.setTypeface(mono, Typeface.BOLD);
        title.setPadding(0, dp(6), 0, dp(2));
        card.addView(title);

        TextView validity = new TextView(this);
        validity.setText(info.validitySummary);
        validity.setTextColor(validityColor(info));
        validity.setTextSize(12);
        validity.setTypeface(mono, Typeface.BOLD);
        card.addView(validity);

        if (info.selfSigned) {
            TextView selfSigned = new TextView(this);
            selfSigned.setText(R.string.ssl_value_self_signed);
            selfSigned.setTextColor(theme.textDim);
            selfSigned.setTextSize(11);
            selfSigned.setPadding(0, dp(2), 0, 0);
            card.addView(selfSigned);
        }

        if (info.chainBreak) {
            TextView broken = new TextView(this);
            broken.setText(R.string.ssl_chain_break);
            broken.setTextColor(theme.errorText);
            broken.setTextSize(11);
            broken.setTypeface(mono, Typeface.BOLD);
            broken.setPadding(dp(6), dp(6), dp(6), dp(6));
            LinearLayout.LayoutParams brokenLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            brokenLp.topMargin = dp(6);
            broken.setLayoutParams(brokenLp);
            broken.setBackground(roundedFill(
                    Colors.blend(theme.consoleBg, theme.errorText, 0.18f), theme.errorText));
            card.addView(broken);
        }

        // ── Subject / Issuer ───────────────────────────────────────────────
        sectionTitle(card, getString(R.string.ssl_section_subject));
        addDn(card, info.subject);

        sectionTitle(card, getString(R.string.ssl_section_issuer));
        addDn(card, info.issuer);

        // ── Validity ───────────────────────────────────────────────────────
        sectionTitle(card, getString(R.string.ssl_section_validity));
        field(card, getString(R.string.ssl_label_not_before), info.notBefore, theme.text, true);
        field(card, getString(R.string.ssl_label_not_after), info.notAfter, theme.text, true);
        field(card, getString(R.string.ssl_label_remaining), info.validitySummary,
                validityColor(info), true);

        // ── Details ────────────────────────────────────────────────────────
        sectionTitle(card, getString(R.string.ssl_section_details));
        field(card, getString(R.string.ssl_label_serial), info.serial, theme.text, true);
        field(card, getString(R.string.ssl_label_sig_alg), info.signatureAlgorithm, theme.text, true);
        field(card, getString(R.string.ssl_label_key), info.publicKey, theme.text, true);
        field(card, getString(R.string.ssl_label_version), info.version, theme.text, true);

        // ── SANs ───────────────────────────────────────────────────────────
        sectionTitle(card, getString(R.string.ssl_section_san));
        if (info.subjectAltNames.isEmpty()) {
            bullet(card, getString(R.string.ssl_value_none), theme.textDim);
        } else {
            for (String[] san : info.subjectAltNames) {
                field(card, san[0], san[1], theme.text, true);
            }
        }

        // ── Constraints and usage ──────────────────────────────────────────
        sectionTitle(card, getString(R.string.ssl_section_usage));
        field(card, getString(R.string.ssl_label_basic_constraints), info.basicConstraints,
                info.isCa ? theme.successText : theme.text, false);
        field(card, getString(R.string.ssl_label_key_usage),
                info.keyUsage.isEmpty() ? getString(R.string.ssl_value_none)
                        : joinList(info.keyUsage),
                info.keyUsage.isEmpty() ? theme.textDim : theme.text, false);
        field(card, getString(R.string.ssl_label_ext_key_usage),
                info.extKeyUsage.isEmpty() ? getString(R.string.ssl_value_none)
                        : joinList(info.extKeyUsage),
                info.extKeyUsage.isEmpty() ? theme.textDim : theme.text, false);

        // ── Fingerprints ───────────────────────────────────────────────────
        sectionTitle(card, getString(R.string.ssl_section_fingerprints));
        field(card, getString(R.string.ssl_label_sha256), info.sha256, theme.text, true);
        field(card, getString(R.string.ssl_label_sha1), info.sha1, theme.text, true);

        return row;
    }

    private int validityColor(CertInfo info) {
        switch (info.validityState) {
            case CertInfo.EXPIRED:
            case CertInfo.NOT_YET_VALID:
                return theme.errorText;
            case CertInfo.EXPIRING:
                return Colors.blend(theme.errorText, theme.successText, 0.35f);
            default:
                return theme.successText;
        }
    }

    private void addDn(LinearLayout parent, List<String[]> rdns) {
        if (rdns.isEmpty()) {
            bullet(parent, getString(R.string.ssl_value_none), theme.textDim);
            return;
        }
        for (String[] rdn : rdns) {
            field(parent, rdn[0], rdn[1], theme.text, false);
        }
    }

    private void sectionTitle(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(theme.accent);
        tv.setTextSize(10);
        tv.setAllCaps(true);
        tv.setTypeface(mono, Typeface.BOLD);
        tv.setPadding(0, dp(10), 0, dp(3));
        parent.addView(tv);

        View line = new View(this);
        line.setBackgroundColor(theme.separator);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.bottomMargin = dp(4);
        parent.addView(line, lp);
    }

    private void field(LinearLayout parent, String label, String value, int valueColor, boolean monospace) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(1), 0, dp(1));

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextColor(theme.textDim);
        labelView.setTextSize(11);
        row.addView(labelView, new LinearLayout.LayoutParams(
                dp(96), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView valueView = new TextView(this);
        valueView.setText(value == null ? getString(R.string.ssl_value_none) : value);
        valueView.setTextColor(valueColor);
        valueView.setTextSize(11);
        if (monospace) valueView.setTypeface(mono);
        valueView.setTextIsSelectable(true);
        row.addView(valueView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        parent.addView(row);
    }

    private void bullet(LinearLayout parent, String text, int color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(11);
        parent.addView(tv);
    }

    private String joinList(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(v);
        }
        return sb.toString();
    }

    // ── Actions ─────────────────────────────────────────────────────────────

    private void copyPem(CertInfo info) {
        if (info.pem == null) return;
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText(
                        getString(R.string.ssl_clip_label), info.pem));
                Toast.makeText(this, R.string.ssl_toast_pem_copied, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.ssl_status_error, messageOf(e)),
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** DER → base64 with the standard armour, wrapped at 64 characters. */
    private static String toPem(byte[] der) {
        String b64 = Base64.encodeToString(der, Base64.NO_WRAP);
        StringBuilder sb = new StringBuilder(b64.length() + 80);
        sb.append("-----BEGIN CERTIFICATE-----\n");
        for (int i = 0; i < b64.length(); i += 64) {
            sb.append(b64, i, Math.min(i + 64, b64.length())).append('\n');
        }
        sb.append("-----END CERTIFICATE-----\n");
        return sb.toString();
    }

    // ── Small helpers ───────────────────────────────────────────────────────

    private static String digest(byte[] data, String algorithm) throws Exception {
        return hexPairs(MessageDigest.getInstance(algorithm).digest(data));
    }

    /** {@code AB:CD:EF…} — the form every other certificate tool prints. */
    private static String hexPairs(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(HEX[(bytes[i] >> 4) & 0x0F]).append(HEX[bytes[i] & 0x0F]);
        }
        return sb.toString();
    }

    private static List<X509Certificate> toX509(Certificate[] certs) {
        List<X509Certificate> out = new ArrayList<>();
        if (certs == null) return out;
        for (Certificate c : certs) {
            if (c instanceof X509Certificate) out.add((X509Certificate) c);
        }
        return out;
    }

    private static String messageOf(Throwable t) {
        String msg = t.getMessage();
        if (msg == null || msg.isEmpty()) msg = t.getClass().getSimpleName();
        return msg;
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (Exception ignored) { }
    }

    private void postError(String message) {
        ui.post(() -> {
            if (statusText == null) return;
            statusText.setText(getString(R.string.ssl_status_error, message));
            if (chainContainer != null) {
                chainContainer.removeAllViews();
                TextView tv = new TextView(this);
                tv.setText(getString(R.string.ssl_status_error, message));
                tv.setTextColor(theme.errorText);
                tv.setTextSize(12);
                tv.setTypeface(mono);
                tv.setPadding(dp(10), dp(10), dp(10), dp(10));
                tv.setBackground(roundedFill(
                        Colors.blend(theme.consoleBg, theme.errorText, 0.15f), theme.errorText));
                chainContainer.addView(tv);
            }
        });
    }

    private TextView createButton(String text, int color) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextColor(color);
        btn.setTextSize(12);
        btn.setTypeface(mono, Typeface.BOLD);
        btn.setPadding(dp(12), dp(8), dp(12), dp(8));
        btn.setBackgroundResource(android.R.drawable.list_selector_background);
        return btn;
    }

    private GradientDrawable roundedFill(int fill, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(6));
        d.setColor(fill);
        d.setStroke(1, stroke);
        return d;
    }

    private GradientDrawable roundedSolid(int fill) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(3));
        d.setColor(fill);
        return d;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    /** @see Dialogs#rounded */
    private com.google.android.material.dialog.MaterialAlertDialogBuilder newRoundedDialog() {
        return Dialogs.rounded(this);
    }

    // ── Models ──────────────────────────────────────────────────────────────

    /** A whole chain plus, for a live fetch, what was negotiated to get it. */
    private static final class Chain {
        List<CertInfo> certs = new ArrayList<>();
        String peer;
        String protocol;
        String cipher;
        long elapsedMs;
    }

    /** Everything one certificate contributes to the screen, pre-rendered off the UI thread. */
    private static final class CertInfo {
        static final int VALID = 0;
        static final int EXPIRING = 1;
        static final int EXPIRED = 2;
        static final int NOT_YET_VALID = 3;

        int index;
        String role;
        String commonName;
        List<String[]> subject = new ArrayList<>();
        List<String[]> issuer = new ArrayList<>();
        boolean selfSigned;
        boolean chainBreak;

        String notBefore;
        String notAfter;
        long daysRemaining;
        int validityState;
        String validitySummary;

        String serial;
        String signatureAlgorithm;
        String publicKey;
        String version;

        List<String[]> subjectAltNames = new ArrayList<>();
        boolean isCa;
        String basicConstraints;
        List<String> keyUsage = new ArrayList<>();
        List<String> extKeyUsage = new ArrayList<>();

        String sha256;
        String sha1;
        String pem;
    }
}
