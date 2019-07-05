package me.devsaki.hentoid.activities.sources;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.DrawableRes;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.widget.SwipeRefreshLayout;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import me.devsaki.hentoid.HentoidApp;
import me.devsaki.hentoid.R;
import me.devsaki.hentoid.abstracts.BaseActivity;
import me.devsaki.hentoid.activities.DownloadsActivity;
import me.devsaki.hentoid.activities.QueueActivity;
import me.devsaki.hentoid.activities.bundles.BaseWebActivityBundle;
import me.devsaki.hentoid.database.ObjectBoxDB;
import me.devsaki.hentoid.database.domains.Content;
import me.devsaki.hentoid.database.domains.QueueRecord;
import me.devsaki.hentoid.enums.Site;
import me.devsaki.hentoid.enums.StatusContent;
import me.devsaki.hentoid.listener.ResultListener;
import me.devsaki.hentoid.parsers.ContentParserFactory;
import me.devsaki.hentoid.parsers.content.ContentParser;
import me.devsaki.hentoid.services.ContentQueueManager;
import me.devsaki.hentoid.util.Consts;
import me.devsaki.hentoid.util.FileHelper;
import me.devsaki.hentoid.util.Helper;
import me.devsaki.hentoid.util.HttpHelper;
import me.devsaki.hentoid.util.PermissionUtil;
import me.devsaki.hentoid.util.Preferences;
import me.devsaki.hentoid.util.ToastUtil;
import me.devsaki.hentoid.views.ObservableWebView;
import okhttp3.Response;
import pl.droidsonroids.jspoon.HtmlAdapter;
import pl.droidsonroids.jspoon.Jspoon;
import timber.log.Timber;

/**
 * Browser activity which allows the user to navigate a supported source.
 * No particular source should be filtered/defined here.
 * The source itself should contain every method it needs to function.
 * <p>
 * todo issue:
 * {@link #checkPermissions()} causes the app to reset unexpectedly. If permission is integral to
 * this activity's function, it is recommended to request for this permission and show rationale if
 * permission request is denied
 */
public abstract class BaseWebActivity extends BaseActivity implements ResultListener<Content> {

    protected static final int MODE_DL = 0;
    protected static final int MODE_QUEUE = 1;
    protected static final int MODE_READ = 2;

    // UI
    protected ObservableWebView webView;                                                // Associated webview
    private FloatingActionButton fabAction, fabRefreshOrStop, fabHome;       // Action buttons
    private SwipeRefreshLayout swipeLayout;

    // Content currently viewed
    private Content currentContent;
    // Database
    private ObjectBoxDB db;
    // Indicated which mode the download FAB is in
    protected int fabActionMode;
    private boolean fabActionEnabled;

    protected CustomWebViewClient webClient;

    // List of blocked content (ads or annoying images) -- will be replaced by a blank stream
    private static final List<String> universalBlockedContent = new ArrayList<>();      // Universal list (applied to all sites)
    private List<String> localBlockedContent;                                           // Local list (applied to current site)

    static {
        universalBlockedContent.add("exoclick.com");
        universalBlockedContent.add("juicyadultads.com");
        universalBlockedContent.add("juicyads.com");
        universalBlockedContent.add("exosrv.com");
        universalBlockedContent.add("hentaigold.net");
        universalBlockedContent.add("ads.php");
        universalBlockedContent.add("ads.js");
        universalBlockedContent.add("pop.js");
        universalBlockedContent.add("trafficsan.com");
        universalBlockedContent.add("contentabc.com");
        universalBlockedContent.add("bebi.com");
        universalBlockedContent.add("aftv-serving.bid");
        universalBlockedContent.add("smatoo.net");
        universalBlockedContent.add("adtng.net");
        universalBlockedContent.add("adtng.com");
        universalBlockedContent.add("popads.net");
        universalBlockedContent.add("adsco.re");
        universalBlockedContent.add("s24hc8xzag.com");
        universalBlockedContent.add("/nutaku/");
    }

    protected abstract CustomWebViewClient getWebClient();

    abstract Site getStartSite();

    /**
     * Add an content block filter to current site
     *
     * @param filter Filter to addAll to content block system
     */
    protected void addContentBlockFilter(String[] filter) {
        if (null == localBlockedContent) localBlockedContent = new ArrayList<>();
        Collections.addAll(localBlockedContent, filter);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_base_web);

        db = ObjectBoxDB.getInstance(this);

        if (getStartSite() == null) {
            Timber.w("Site is null!");
        } else {
            Timber.d("Loading site: %s", getStartSite());
        }

        fabAction = findViewById(R.id.fabAction);
        fabRefreshOrStop = findViewById(R.id.fabRefreshStop);
        fabHome = findViewById(R.id.fabHome);

        fabActionEnabled = false;

        initWebView();
        initSwipeLayout();

        String intentUrl = "";
        if (getIntent().getExtras() != null) {
            BaseWebActivityBundle.Parser parser = new BaseWebActivityBundle.Parser(getIntent().getExtras());
            intentUrl = parser.getUrl();
        }
        webView.loadUrl(0 == intentUrl.length() ? getStartSite().getUrl() : intentUrl);
    }

    @Override
    protected void onResume() {
        super.onResume();

        checkPermissions();
    }

    @Override
    protected void onDestroy() {
        if (webClient != null) webClient.destroy();
        webClient = null;

        if (webView != null) {
            // the WebView must be removed from the view hierarchy before calling destroy
            // to prevent a memory leak
            // See https://developer.android.com/reference/android/webkit/WebView.html#destroy%28%29
            ((ViewGroup) webView.getParent()).removeView(webView);
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }

        super.onDestroy();
    }

    // Validate permissions
    private void checkPermissions() {
        if (PermissionUtil.checkExternalStoragePermission(this)) {
            Timber.d("Storage permission allowed!");
        } else {
            Timber.d("Storage permission denied!");
            reset();
        }
    }

    private void reset() {
        Helper.reset(HentoidApp.getAppContext(), this);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void initWebView() {
        webView = findViewById(R.id.wbMain);
        webView.setHapticFeedbackEnabled(false);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                super.onProgressChanged(view, newProgress);
                if (newProgress == 100) {
                    swipeLayout.post(() -> swipeLayout.setRefreshing(false));
                } else {
                    swipeLayout.post(() -> swipeLayout.setRefreshing(true));
                }
            }
        });
        webView.setOnScrollChangedCallback((l, t) -> {
            if (!webClient.isWebViewLoading()) {
                if (webView.canScrollVertically(1) || t == 0) {
                    fabRefreshOrStop.show();
                    fabHome.show();
                    if (fabActionEnabled) fabAction.show();
                } else {
                    fabRefreshOrStop.hide();
                    fabHome.hide();
                    fabAction.hide();
                }
            }
        });

        boolean bWebViewOverview = Preferences.getWebViewOverview();
        int webViewInitialZoom = Preferences.getWebViewInitialZoom();

        if (bWebViewOverview) {
            webView.getSettings().setLoadWithOverviewMode(false);
            webView.setInitialScale(webViewInitialZoom);
            Timber.d("WebView Initial Scale: %s%%", webViewInitialZoom);
        } else {
            webView.setInitialScale(Preferences.Default.PREF_WEBVIEW_INITIAL_ZOOM_DEFAULT);
            webView.getSettings().setLoadWithOverviewMode(true);
        }

        webClient = getWebClient();
        webView.setWebViewClient(webClient);

        WebSettings webSettings = webView.getSettings();
        webSettings.setBuiltInZoomControls(true);
        webSettings.setDisplayZoomControls(false);

        webSettings.setUserAgentString(Consts.USER_AGENT_NEUTRAL);

        webSettings.setDomStorageEnabled(true);
        webSettings.setUseWideViewPort(true);
        webSettings.setJavaScriptEnabled(true);
        webSettings.setLoadWithOverviewMode(true);
    }

    private void initSwipeLayout() {
        swipeLayout = findViewById(R.id.swipe_container);
        swipeLayout.setOnRefreshListener(() -> {
            if (!swipeLayout.isRefreshing() || !webClient.isWebViewLoading()) {
                webView.reload();
            }
        });
        swipeLayout.setColorSchemeResources(
                android.R.color.holo_blue_bright,
                android.R.color.holo_green_light,
                android.R.color.holo_orange_light,
                android.R.color.holo_red_light);
    }

    public void onRefreshStopFabClick(View view) {
        if (webClient.isWebViewLoading()) {
            webView.stopLoading();
        } else {
            webView.reload();
        }
    }

    private void goHome() {
        Intent intent = new Intent(this, DownloadsActivity.class);
        // If FLAG_ACTIVITY_CLEAR_TOP is not set,
        // it can interfere with Double-Back (press back twice) to exit
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        finish();
    }

    @Override
    public void onBackPressed() {
        if (!webView.canGoBack()) {
            goHome();
        }
    }

    /**
     * Listener for Home floating action button : go back to Library view
     *
     * @param view Calling view (part of the mandatory signature)
     */
    public void onHomeFabClick(View view) {
        goHome();
    }

    /**
     * Listener for Action floating action button : download content, view queue or read content
     *
     * @param view Calling view (part of the mandatory signature)
     */
    public void onActionFabClick(View view) {
        if (MODE_DL == fabActionMode) processDownload();
        else if (MODE_QUEUE == fabActionMode) goToQueue();
        else if (MODE_READ == fabActionMode) {
            if (currentContent != null) {
                currentContent = db.selectContentByUrl(currentContent.getUrl());
                if (currentContent != null) {
                    if (StatusContent.DOWNLOADED == currentContent.getStatus()
                            || StatusContent.ERROR == currentContent.getStatus()
                            || StatusContent.MIGRATED == currentContent.getStatus()) {
                        FileHelper.openContent(this, currentContent);
                    } else {
                        fabAction.hide();
                    }
                }
            }
        }
    }

    private void changeFabActionMode(int mode) {
        @DrawableRes int resId = R.drawable.ic_menu_about;
        if (MODE_DL == mode) {
            resId = R.drawable.ic_action_download;
        } else if (MODE_QUEUE == mode) {
            resId = R.drawable.ic_queued;
        } else if (MODE_READ == mode) {
            resId = R.drawable.ic_action_play;
        }
        fabActionMode = mode;
        fabAction.setImageResource(resId);
        fabActionEnabled = true;
        fabAction.show();
    }

    /**
     * Add current content (i.e. content of the currently viewed book) to the download queue
     */
    void processDownload() {
        if (null == currentContent) return;

        if (currentContent.getId() > 0)
            currentContent = db.selectContentById(currentContent.getId());

        if (null == currentContent) return;

        if (StatusContent.DOWNLOADED == currentContent.getStatus()) {
            ToastUtil.toast(this, R.string.already_downloaded);
            changeFabActionMode(MODE_READ);
            return;
        }
        ToastUtil.toast(this, R.string.add_to_queue);

        currentContent.setDownloadDate(new Date().getTime())
                .setStatus(StatusContent.DOWNLOADING);
        db.insertContent(currentContent);

        List<QueueRecord> queue = db.selectQueue();
        int lastIndex = 1;
        if (queue.size() > 0) {
            lastIndex = queue.get(queue.size() - 1).rank + 1;
        }
        db.insertQueue(currentContent.getId(), lastIndex);

        ContentQueueManager.getInstance().resumeQueue(this);

        changeFabActionMode(MODE_QUEUE);
    }

    private void goToQueue() {
        Intent intent = new Intent(this, QueueActivity.class);
        // If FLAG_ACTIVITY_CLEAR_TOP is not set,
        // it can interfere with Double-Back (press back twice) to exit
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        finish();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_BACK) {
            WebBackForwardList webBFL = webView.copyBackForwardList();
            int i = webBFL.getCurrentIndex();
            do {
                i--;
            }
            while (i >= 0 && webView.getOriginalUrl()
                    .equals(webBFL.getItemAtIndex(i).getOriginalUrl()));
            if (webView.canGoBackOrForward(i - webBFL.getCurrentIndex())) {
                webView.goBackOrForward(i - webBFL.getCurrentIndex());
            } else {
                super.onBackPressed();
            }

            return true;
        }

        return false;
    }

    /**
     * Display webview controls according to designated content
     *
     * @param content Currently displayed content
     */
    void processContent(Content content) {
        if (null == content || null == content.getUrl()) {
            return;
        }

        Timber.i("Content URL : %s", content.getUrl());
        Content contentDB = db.selectContentByUrl(content.getUrl());

        boolean isInCollection = (contentDB != null && (
                contentDB.getStatus().equals(StatusContent.DOWNLOADED)
                        || contentDB.getStatus().equals(StatusContent.MIGRATED)
                        || contentDB.getStatus().equals(StatusContent.ERROR)
        ));
        boolean isInQueue = (contentDB != null && (
                contentDB.getStatus().equals(StatusContent.DOWNLOADING)
                        || contentDB.getStatus().equals(StatusContent.PAUSED)
        ));

        if (!isInCollection && !isInQueue) {
            if (null == contentDB) {    // The book has just been detected -> finalize before saving in DB
                content.setStatus(StatusContent.SAVED);
                content.populateAuthor();
                db.insertContent(content);
            } else {
                content = contentDB;
            }
            changeFabActionMode(MODE_DL);
        }

        if (isInCollection) changeFabActionMode(MODE_READ);
        if (isInQueue) changeFabActionMode(MODE_QUEUE);

        currentContent = content;
    }

    public void onResultReady(Content results, long totalContent) {
        processContent(results);
    }

    public void onResultFailed(String message) {
        runOnUiThread(() -> ToastUtil.toast(HentoidApp.getAppContext(), R.string.web_unparsable));
    }

    /**
     * Indicates if the given URL is forbidden by the current content filters
     *
     * @param url URL to be examinated
     * @return True if URL is forbidden according to current filters; false if not
     */
    protected boolean isUrlForbidden(String url) {
        for (String s : universalBlockedContent) {
            if (url.contains(s)) return true;
        }
        if (localBlockedContent != null)
            for (String s : localBlockedContent) {
                if (url.contains(s)) return true;
            }

        return false;
    }


    abstract class CustomWebViewClient extends WebViewClient {

        private final Jspoon jspoon = Jspoon.create();
        protected final CompositeDisposable compositeDisposable = new CompositeDisposable();
        private final ByteArrayInputStream nothing = new ByteArrayInputStream("".getBytes());
        protected final ResultListener<Content> listener;
        private final Pattern filteredUrlPattern;
        private final HtmlAdapter<ContentParser> htmlAdapter;

        private String restrictedDomainName = "";

        // Resource loading tracking
        private Map<String, Integer> loadedUrls = new HashMap<>();
        private int loadIndex = 0;


        @SuppressWarnings("unchecked")
        CustomWebViewClient(String filteredUrl, ResultListener<Content> listener) {
            this.listener = listener;

            Class c = ContentParserFactory.getInstance().getContentClass(getStartSite());
            htmlAdapter = jspoon.adapter(c); // Unchecked but alright

            if (filteredUrl.length() > 0) filteredUrlPattern = Pattern.compile(filteredUrl);
            else filteredUrlPattern = null;
        }

        void destroy() {
            Timber.d("WebClient destroyed");
            compositeDisposable.clear();
        }

        void restrictTo(String s) {
            restrictedDomainName = s;
        }

        private boolean isPageFiltered(String url) {
            if (null == filteredUrlPattern) return false;

            Matcher matcher = filteredUrlPattern.matcher(url);
            return matcher.find();
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            String hostStr = Uri.parse(url).getHost();
            return hostStr != null && !hostStr.contains(restrictedDomainName);
        }

        @TargetApi(Build.VERSION_CODES.N)
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String hostStr = Uri.parse(request.getUrl().toString()).getHost();
            return hostStr != null && !hostStr.contains(restrictedDomainName);
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            loadedUrls.put(url, loadIndex++);
            fabRefreshOrStop.setImageResource(R.drawable.ic_action_clear);
            fabRefreshOrStop.show();
            fabHome.show();

            fabAction.hide();
            fabActionEnabled = false;

//            if (isPageFiltered(url)) onGalleryFound(url);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            loadedUrls.remove(url);
            loadIndex--;
            fabRefreshOrStop.setImageResource(R.drawable.ic_action_refresh);
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(@NonNull WebView view,
                                                          @NonNull String url) {
            if (isUrlForbidden(url)) {
                return new WebResourceResponse("text/plain", "utf-8", nothing);
            } else {
                // Don't parse anything else than the main page
                // NB : works because onPageStarted is called _after_ shouldInterceptRequest
                if (isPageFiltered(url) && !isMainPageLoading()) return parseResponse(url, null);
                else return super.shouldInterceptRequest(view, url);
            }
        }

        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
        @Override
        public WebResourceResponse shouldInterceptRequest(@NonNull WebView view,
                                                          @NonNull WebResourceRequest request) {
            String url = request.getUrl().toString();
            if (isUrlForbidden(url)) {
                return new WebResourceResponse("text/plain", "utf-8", nothing);
            } else {
                // Don't parse anything else than the main page
                // NB : works because onPageStarted is called _after_ shouldInterceptRequest
                if (isPageFiltered(url) && !isMainPageLoading())
                    return parseResponse(url, request.getRequestHeaders());
                else return super.shouldInterceptRequest(view, request);
            }
        }

        private WebResourceResponse parseResponse(@NonNull String urlStr, @Nullable Map<String, String> headers) {
            List<Pair<String, String>> headersList = new ArrayList<>();

            if (headers != null)
                for (String key : headers.keySet())
                    headersList.add(new Pair<>(key, headers.get(key)));

            try {
                Response response = HttpHelper.getOnlineResource(urlStr, headersList, getStartSite().canKnowHentoidAgent());
                if (null == response.body()) throw new IOException("Empty body");

                URL url = new URL(urlStr);

                // Response body bytestream need to be duplicated
                // because Jsoup closes it, which makes it unavailable for the WebView to use
                ByteArrayOutputStream baos = new ByteArrayOutputStream();

                // TODO : encapsulate and optimize that
                byte[] buffer = new byte[1024];
                int len;
                while ((len = response.body().byteStream().read(buffer)) > -1) {
                    baos.write(buffer, 0, len);
                }
                baos.flush();

                InputStream is1 = new ByteArrayInputStream(baos.toByteArray());
                InputStream is2 = new ByteArrayInputStream(baos.toByteArray());

                compositeDisposable.add(
                        Single.fromCallable(() -> htmlAdapter.fromInputStream(is1, url))
                                .observeOn(AndroidSchedulers.mainThread())
                                .subscribe(
                                        metadata -> listener.onResultReady(metadata.toContent(), 1),
                                        throwable -> {
                                            Timber.e(throwable, "Error parsing content.");
                                            listener.onResultFailed("");
                                        })
                );

                return HttpHelper.okHttpResponseToWebResourceResponse(response, is2);
            } catch (MalformedURLException e) {
                Timber.e(e, "Malformed URL : %s", urlStr);
            } catch (IOException e) {
                Timber.e(e);
            }
            return null;
        }

        /**
         * Indicated whether the current main webpage is still loading or not
         * <p>
         * NB : "main webpage" refers to the 1st page ever loaded when querying the currently opened URL
         * The difference with onPageFinished/started is that it doesn't take iframes/framesets into account
         *
         * @return True if current main webpage is being loaded; false if not
         */
        private boolean isMainPageLoading() {
            return loadedUrls.containsValue(0); // Index 0 is the main webpage
        }

        boolean isWebViewLoading() {
            return 0 == loadIndex;
        }
    }
}
