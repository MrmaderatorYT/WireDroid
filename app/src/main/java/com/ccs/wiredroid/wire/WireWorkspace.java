package com.ccs.wiredroid.wire;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.*;
import android.widget.Toast;
import android.app.Activity;
import java.util.function.Consumer;
import com.ccs.wiredroid.ui.FileOpenActivity;
import com.ccs.wiredroid.ui.MainActivity;
import com.ccs.wiredroid.util.AppPreferences;
import com.ccs.wiredroid.util.AppTheme;
import org.json.JSONObject;
import java.io.*;
import java.util.Collections;
import org.json.JSONArray;
import java.util.LinkedHashMap;
import java.util.Map;

/** Local-only visual workspace. The JS bridge is never exposed to remote pages or programs. */
public final class WireWorkspace implements AutoCloseable {
    public interface DiagnosticOpener {void open(File file,int line,int column);}
    private static final String ORIGIN = "https://wiredroid.local";
    private final Activity activity;
    private final Consumer<File> openCode;
    private final Runnable saveCode;
    private final java.util.function.BooleanSupplier editorSaved;
    private final DiagnosticOpener openDiagnostic;
    private volatile boolean runRequested,closed,paused;
    private volatile long eventGeneration;
    private final Map<String,String> recoveries=new LinkedHashMap<>();
    private WireUsbManager usb;
    private final java.util.concurrent.ExecutorService io=java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile String uploadResult="{\"state\":\"idle\"}";
    private volatile java.util.concurrent.Future<?> uploadJob;
    private volatile long uploadGeneration;
    private File root;
    private WebView web;
    private boolean loaded;
    private EmbeddedRunner embedded;

    @SuppressLint("SetJavaScriptEnabled")
    public WireWorkspace(Activity activity, File project, Consumer<File> openCode, Runnable saveCode) throws IOException {
        this(activity,project,openCode,saveCode,(file,line,column)->openCode.accept(file));
    }

    @SuppressLint("SetJavaScriptEnabled")
    public WireWorkspace(Activity activity, File project, Consumer<File> openCode, Runnable saveCode, DiagnosticOpener openDiagnostic) throws IOException {
        this(activity,project,openCode,saveCode,openDiagnostic,()->{saveCode.run();return true;});
    }

    @SuppressLint("SetJavaScriptEnabled")
    public WireWorkspace(Activity activity,File project,Consumer<File> openCode,Runnable saveCode,
                         DiagnosticOpener openDiagnostic,java.util.function.BooleanSupplier editorSaved)throws IOException {
        this.activity = activity;this.openCode = openCode;this.saveCode = saveCode;this.openDiagnostic=openDiagnostic;this.editorSaved=editorSaved;
        root = project.getCanonicalFile();
        if (!WireProject.isCircuit(root)) throw new IOException("Створіть embedded-проєкт WireDroid");
        embedded = new EmbeddedRunner(activity, root, (id, event) -> {
            long generation=eventGeneration;
            if(closed||paused)return;
            activity.runOnUiThread(() -> {
                if (!closed&&!paused&&generation==eventGeneration&&web != null && loaded) web.evaluateJavascript("window.WireApp && WireApp.nativeEvent(" + JSONObject.quote(id) + "," + event.toString() + ")", null);
            });
        });
        AppPreferences prefs = new AppPreferences(activity);
        prefs.setProjectRoot(root.getAbsolutePath()); prefs.addRecentProject(root.getAbsolutePath());
        AppTheme theme = AppTheme.byId(prefs.getThemeId(), prefs);

        web = new WebView(activity);
        web.setBackgroundColor(theme.bg);

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return localResponse(request.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true; // No navigation away from the trusted workspace.
            }
            @Override public void onPageFinished(WebView view, String url) { loaded = url.equals(ORIGIN + "/index.html"); if (loaded) applyTheme(); }
        });
        // Worker requests do not necessarily use WebViewClient; serve them explicitly as well.
        ServiceWorkerController controller = ServiceWorkerController.getInstance();
        controller.getServiceWorkerWebSettings().setAllowFileAccess(false);
        controller.getServiceWorkerWebSettings().setAllowContentAccess(false);
        final android.content.res.AssetManager assets = activity.getAssets();
        controller.setServiceWorkerClient(new ServiceWorkerClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
                return assetResponse(assets, request.getUrl());
            }
        });
        web.addJavascriptInterface(new Bridge(), "WireHost");
        web.loadUrl(ORIGIN + "/index.html");
    }

    private WebResourceResponse localResponse(Uri uri) { return assetResponse(activity.getAssets(), uri); }

    private static WebResourceResponse assetResponse(android.content.res.AssetManager assets, Uri uri) {
        try {
            if (!"https".equals(uri.getScheme()) || !"wiredroid.local".equals(uri.getHost()) || uri.getPort() != -1)
                return denied();
            String path = uri.getPath();
            if (path == null || path.contains("..") || path.contains("\\") || !path.startsWith("/")) return denied();
            String mime = path.endsWith(".html") ? "text/html" : path.endsWith(".js") ? "application/javascript"
                    : path.endsWith(".css") ? "text/css" : path.endsWith(".svg") ? "image/svg+xml"
                    : path.endsWith(".json") ? "application/json" : "text/plain";
            return new WebResourceResponse(mime, "UTF-8", 200, "OK",
                    Collections.singletonMap("Cache-Control", "no-cache"), assets.open("wiredroid" + path));
        } catch (IOException e) { return denied(); }
    }

    private static WebResourceResponse denied() {
        return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", Collections.emptyMap(),
                new ByteArrayInputStream(new byte[0]));
    }

    public final class Bridge {
        @JavascriptInterface public void openExamples() {
            activity.runOnUiThread(()->{saveCode.run();pause();WireExamples.show(activity,file->WireActivity.launch(activity,file,false));});
        }
        @JavascriptInterface public String projectName() { return root.getName(); }
        @JavascriptInterface public synchronized boolean shouldRun() { boolean requested = runRequested;runRequested = false;return requested&&!closed&&!paused; }
        @JavascriptInterface public String getTheme() {
            AppPreferences prefs = new AppPreferences(activity);
            AppTheme theme = AppTheme.byId(prefs.getThemeId(), prefs);
            try {return new JSONObject().put("dark",theme.dark).put("embedded",true)
                .put("bg",color(theme.bg)).put("panel",color(theme.toolbar)).put("text",color(theme.text))
                .put("muted",color(theme.textDim)).put("accent",color(theme.accent))
                .put("line",color(theme.separator)).put("console",color(theme.consoleBg))
                .put("success",color(theme.successText)).put("error",color(theme.errorText)).toString();
            }catch(Exception ignored){return "{}";}
        }
        @JavascriptInterface public String loadScene() {
            try { File scene = new File(root, WireProject.SCENE).getCanonicalFile();
                if (!scene.getParentFile().equals(root)) throw new IOException("Схема за межами проєкту");
                RecoveryStore.Read recovered=WireProject.readRecovered(scene,2*1024*1024);
                if(recovered.recovered)recordRecovery(scene,recovered.reason);
                return WireProject.migrateScene(recovered.text); }
            catch (Exception e) { return "WIREDROID_ERROR:" + e.getMessage(); }
        }
        @JavascriptInterface public synchronized String recoveryInfo() {
            JSONArray items=new JSONArray();synchronized(recoveries){for(Map.Entry<String,String> entry:recoveries.entrySet())try{items.put(new JSONObject().put("file",entry.getKey()).put("message",entry.getValue()));}catch(Exception ignored){}}
            return items.toString();
        }
        @JavascriptInterface public String parseDiagnostics(String output) {
            try{return CompilerDiagnostics.json(root,output);}catch(Exception e){return "[]";}
        }
        @JavascriptInterface public String getBoardRegistry() {
            try{return BoardRegistry.load(activity).json();}catch(Exception e){return "WIREDROID_ERROR:"+e.getMessage();}
        }
        @JavascriptInterface public String getFirmwareConfig(String id) {
            try{return FirmwareConfig.load(root,id).raw.toString();}catch(Exception e){return "WIREDROID_ERROR:"+e.getMessage();}
        }
        @JavascriptInterface public String saveFirmwareConfig(String id,String raw) {
            try{if(raw.length()>128*1024)throw new IOException("Налаштування завеликі");FirmwareConfig.save(root,id,raw);return "";}catch(Exception e){return String.valueOf(e.getMessage());}
        }
        @JavascriptInterface public String listLibraries() {
            try{return EmbeddedLibraries.list(root).toString();}catch(Exception e){return "WIREDROID_ERROR:"+e.getMessage();}
        }
        @JavascriptInterface public String libraryCompatibility(String architecture,String mode) {
            try{return EmbeddedLibraries.compatibility(root,architecture,mode).toString();}catch(Exception e){return "WIREDROID_ERROR:"+e.getMessage();}
        }
        @JavascriptInterface public String importLibrary(String relative) {
            try {
                File archive=CompilerDiagnostics.resolve(root,relative);
                if(!archive.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".zip"))throw new IOException("Потрібен ZIP Arduino-бібліотеки");
                return EmbeddedLibraries.install(root,archive,libraryCache()).toString();
            }catch(Exception e){return "WIREDROID_ERROR:"+e.getMessage();}
        }
        @JavascriptInterface public String restoreLibraries() {
            try{EmbeddedLibraries.restore(root,libraryCache());return "";}catch(Exception e){return String.valueOf(e.getMessage());}
        }
        @JavascriptInterface public String removeLibrary(String name) {
            try{EmbeddedLibraries.remove(root,name);return "";}catch(Exception e){return String.valueOf(e.getMessage());}
        }
        @JavascriptInterface public void chooseLibrary() {
            activity.runOnUiThread(()->{
                try {
                    java.util.List<File> files=new java.util.ArrayList<>();collectFiles(root,".zip",files,new java.util.HashSet<>(),0,new int[]{4096});
                    if(files.isEmpty()){Toast.makeText(activity,"Імпортуйте ZIP Arduino-бібліотеки до проєкту через редактор",Toast.LENGTH_LONG).show();return;}
                    String[] names=new String[files.size()];for(int i=0;i<names.length;i++)names[i]=root.toPath().relativize(files.get(i).toPath()).toString();
                    com.ccs.wiredroid.ui.Dialogs.rounded(activity).setTitle("Встановити Arduino-бібліотеку")
                        .setItems(names,(dialog,which)->io.execute(()->{
                            String installed=importLibrary(names[which]);activity.runOnUiThread(()->{if(closed)return;Toast.makeText(activity,installed.startsWith("WIREDROID_ERROR:")?installed.substring(16):"Бібліотеку встановлено",Toast.LENGTH_LONG).show();if(web!=null)web.evaluateJavascript("window.WireApp && WireApp.librariesChanged && WireApp.librariesChanged()",null);});
                        })).setNegativeButton("Скасувати",null).show();
                }catch(Exception e){Toast.makeText(activity,e.getMessage(),Toast.LENGTH_LONG).show();}
            });
        }
        @JavascriptInterface public String usbDevices(){try{return usb().enumerate();}catch(Exception e){return "WIREDROID_ERROR:"+e.getMessage();}}
        @JavascriptInterface public String usbPermission(String name){try{return usb().requestPermission(name);}catch(Exception e){return String.valueOf(e.getMessage());}}
        @JavascriptInterface public String usbOpen(String name,int port,int baud){try{return usb().open(name,port,baud);}catch(Exception e){return String.valueOf(e.getMessage());}}
        @JavascriptInterface public String usbRead(int limit){try{return usb().read(limit);}catch(Exception e){return "";}}
        @JavascriptInterface public String usbWrite(String base64){try{return usb().write(base64);}catch(Exception e){return String.valueOf(e.getMessage());}}
        @JavascriptInterface public String usbStatus(){try{return usb().status().put("uploadResult",new JSONObject(uploadResult)).toString();}catch(Exception e){return "WIREDROID_ERROR:"+e.getMessage();}}
        @JavascriptInterface public String usbClose(){try{return usb().close();}catch(Exception e){return String.valueOf(e.getMessage());}}
        @JavascriptInterface public String usbUploadResult(){return uploadResult;}
        @JavascriptInterface public synchronized String usbUpload(String id) {
            try {
                if(closed)throw new IOException("Панель проєкту закрита");
                if(uploadJob!=null&&!uploadJob.isDone())throw new IOException("Завантаження вже виконується");
                FirmwareConfig settings=FirmwareConfig.load(root,id);JSONObject target=BoardRegistry.load(activity).profile(settings.profile);
                String hex=FirmwareArtifacts.read(root,id);WireUsbManager manager=usb();
                long generation=++uploadGeneration;
                uploadResult="{\"state\":\"running\"}";
                uploadJob=io.submit(()->{
                    try{JSONObject result=manager.upload(hex,target);if(generation==uploadGeneration)uploadResult=new JSONObject().put("state","success").put("result",result).toString();}
                    catch(Exception e){if(generation==uploadGeneration)try{uploadResult=new JSONObject().put("state","error").put("message",String.valueOf(e.getMessage())).toString();}catch(Exception impossible){uploadResult="{\"state\":\"error\"}";}}
                });return "";
            }catch(Exception e){return String.valueOf(e.getMessage());}
        }
        @JavascriptInterface public void usbCancelUpload(){uploadGeneration++;if(usb!=null)usb.cancelUpload();java.util.concurrent.Future<?> job=uploadJob;if(job!=null)job.cancel(true);uploadResult="{\"state\":\"error\",\"message\":\"Завантаження скасовано\"}";}
        @JavascriptInterface public String openDiagnostic(String relative,int line,int column) {
            try {
                File file=CompilerDiagnostics.resolve(root,relative);
                if(line<1||column<1||line>10000000||column>10000000)throw new IOException("Некоректна позиція помилки");
                activity.runOnUiThread(()->{if(!closed)openDiagnostic.open(file,line,column);});return "";
            }catch(Exception e){return String.valueOf(e.getMessage());}
        }
        @JavascriptInterface public synchronized String saveScene(String raw) {
            try {
                WireProject.validateScene(raw);
                File scene = new File(root, WireProject.SCENE).getCanonicalFile();
                if (!scene.getParentFile().equals(root)) throw new IOException("Схема за межами проєкту");
                WireProject.write(scene, raw);return "";
            } catch (Exception e) { return String.valueOf(e.getMessage()); }
        }
        @JavascriptInterface public String ensureSketch(String id, String code) {
            try { if (code.length() > 512 * 1024) throw new IOException("Програма завелика");
                WireProject.ensureSketch(root, id, code);return "";
            } catch (Exception e) { return String.valueOf(e.getMessage()); }
        }
        @JavascriptInterface public String duplicateSketch(String from,String to) {
            try{WireProject.duplicateSketch(root,from,to);return "";}catch(Exception e){return String.valueOf(e.getMessage());}
        }
        @JavascriptInterface public String readSpiceModels() {
            try{return SpiceArtifacts.readModels(root);}catch(Exception e){return "WIREDROID_ERROR:"+e.getMessage();}
        }
        @JavascriptInterface public String saveSpiceModels(String text) {
            try{SpiceArtifacts.saveModels(root,text);return "";}catch(Exception e){return String.valueOf(e.getMessage());}
        }
        @JavascriptInterface public String saveSpice(String name,String text) {
            try{SpiceArtifacts.saveNetlist(root,name,text);return "";}catch(Exception e){return String.valueOf(e.getMessage());}
        }
        @JavascriptInterface public synchronized String saveSignal(String name,String data) {
            try {if(!name.matches("[a-zA-Z0-9_-]{1,80}\\.vcd")||data.length()>2*1024*1024)throw new IOException("Некоректний файл сигналів");File file=new File(root,"signals/"+name).getCanonicalFile();if(!file.toPath().startsWith(root.toPath()))throw new IOException("Файл поза проєктом");WireProject.write(file,data);return "";}catch(Exception e){return String.valueOf(e.getMessage());}
        }
        @JavascriptInterface public String startNative(String id, String pins) { return closed||paused?"Запуск зупинено: відкрийте панель схеми":embedded.start(id, pins); }
        @JavascriptInterface public void saveEditor() {
            long generation=eventGeneration;
            activity.runOnUiThread(() -> {if(closed||paused||generation!=eventGeneration)return;if(!saveBeforeRun())return;if(web!=null)web.evaluateJavascript("WireApp.run(true)",null);});
        }
        @JavascriptInterface public void stopNative() { embedded.stopAll(); }
        @JavascriptInterface public String sendSerial(String id, String text) { return embedded.sendSerial(id, text); }
        @JavascriptInterface public void nativeReply(String id, int value) { embedded.reply(id, value); }
        @JavascriptInterface public void nativeBytesReply(String id,String hex){embedded.replyBytes(id,hex);}
        @JavascriptInterface public String readSketch(String id) {
            try {
                WireProject.ensureSketch(root, id, WireProject.DEFAULT_CODE);
                File file=WireProject.sketch(root,id);RecoveryStore.Read read=WireProject.readRecovered(file,512*1024);
                if(read.recovered)recordRecovery(file,read.reason);return read.text;
            } catch (Exception e) { return "WIREDROID_ERROR:" + e.getMessage(); }
        }
        @JavascriptInterface public String readFirmware(String id) {
            try{return FirmwareArtifacts.read(root,id);}catch(Exception e){return "WIREDROID_ERROR:"+e.getMessage();}
        }
        @JavascriptInterface public void chooseFirmware(String id) {
            activity.runOnUiThread(()->{
                try {
                    WireProject.sketch(root,id);
                    java.util.List<File> files=new java.util.ArrayList<>();
                    collectFiles(root,".hex",files,new java.util.HashSet<>(),0,new int[]{4096});
                    if(files.isEmpty()){Toast.makeText(activity,"Імпортуйте .hex до проєкту через редактор",Toast.LENGTH_LONG).show();return;}
                    String[] names=new String[files.size()];for(int i=0;i<names.length;i++)names[i]=root.toPath().relativize(files.get(i).toPath()).toString();
                    com.ccs.wiredroid.ui.Dialogs.rounded(activity).setTitle("Прошивка Intel HEX")
                        .setItems(names,(dialog,which)->{try{FirmwareArtifacts.importFile(root,id,files.get(which));if(web!=null)web.evaluateJavascript("window.WireApp && WireApp.firmwareChanged && WireApp.firmwareChanged("+JSONObject.quote(id)+")",null);Toast.makeText(activity,"Прошивку імпортовано",Toast.LENGTH_LONG).show();}catch(Exception e){Toast.makeText(activity,e.getMessage(),Toast.LENGTH_LONG).show();}})
                        .setNegativeButton("Скасувати",null).show();
                }catch(Exception e){Toast.makeText(activity,e.getMessage(),Toast.LENGTH_LONG).show();}
            });
        }
        @JavascriptInterface public void chooseSketch(String id) {
            activity.runOnUiThread(() -> {
                try {
                    File target = WireProject.sketch(root, id);
                    java.util.List<File> files = new java.util.ArrayList<>();
                    for (File file : com.ccs.wiredroid.project.ProjectScanner.listAllSources(root)) {
                        String name = file.getName().toLowerCase(java.util.Locale.ROOT);
                        if ((name.endsWith(".ino") || name.endsWith(".cpp")) && !file.getCanonicalFile().equals(target)
                                && file.getCanonicalPath().startsWith(root.getPath() + File.separator)) files.add(file);
                    }
                    if (files.isEmpty()) {Toast.makeText(activity, "Спочатку імпортуйте .cpp/.ino до проєкту через редактор", Toast.LENGTH_LONG).show();return;}
                    String[] names = new String[files.size()];
                    for (int i=0;i<names.length;i++) names[i] = root.toPath().relativize(files.get(i).toPath()).toString();
                    com.ccs.wiredroid.ui.Dialogs.rounded(activity).setTitle("Головний C/C++ скетч плати")
                        .setItems(names, (dialog, which) -> {
                            try {
                                String relative = target.getParentFile().toPath().relativize(files.get(which).getCanonicalFile().toPath()).toString();
                                WireProject.write(target, "#include <Arduino.h>\n#include " + EmbeddedRunner.cppQuote(relative) + "\n");
                                Toast.makeText(activity, "Плата використовує " + names[which], Toast.LENGTH_LONG).show();
                                web.evaluateJavascript("WireApp.reloadPrograms()", null);
                            } catch (Exception e) {Toast.makeText(activity, e.getMessage(), Toast.LENGTH_LONG).show();}
                        }).setNegativeButton("Скасувати", null).show();
                } catch (Exception e) {Toast.makeText(activity, e.getMessage(), Toast.LENGTH_LONG).show();}
            });
        }
        @JavascriptInterface public void editSketch(String id) {
            try {
                WireProject.ensureSketch(root, id, WireProject.DEFAULT_CODE);
                File file = WireProject.sketch(root, id);
                activity.runOnUiThread(() -> openCode.accept(file));
            } catch (Exception e) {
                activity.runOnUiThread(() -> Toast.makeText(activity, e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }
    }

    public WebView getView() {return web;}
    private void recordRecovery(File file,String reason) {
        synchronized(recoveries){recoveries.put(root.toPath().relativize(file.toPath()).toString(),reason);}
    }
    private File libraryCache(){return new File(activity.getFilesDir(),"embedded-library-cache");}
    private synchronized WireUsbManager usb()throws IOException {
        if(closed)throw new IOException("Панель проєкту закрита");if(usb==null)usb=new WireUsbManager(activity);return usb;
    }
    private void collectFiles(File directory,String suffix,java.util.List<File> files,java.util.Set<String> visited,int depth,int[] budget)throws IOException {
        if(depth>32||files.size()>=256||budget[0]<=0)return;
        File canonical=directory.getCanonicalFile();if(!canonical.toPath().startsWith(root.toPath())||!visited.add(canonical.getPath()))return;
        File[] children=canonical.listFiles();if(children==null)return;java.util.Arrays.sort(children,java.util.Comparator.comparing(File::getName));
        for(File file:children){if(--budget[0]<0||files.size()>=256)break;if(file.getName().startsWith("."))continue;if(file.isDirectory())collectFiles(file,suffix,files,visited,depth+1,budget);else if(file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(suffix)){File actual=file.getCanonicalFile();if(actual.toPath().startsWith(root.toPath()))files.add(actual);}}
    }
    private static String color(int value) {return String.format(java.util.Locale.ROOT,"#%06x",value & 0xffffff);}
    public void applyTheme() {
        if(closed||web==null)return;
        AppPreferences prefs = new AppPreferences(activity);
        web.setBackgroundColor(AppTheme.byId(prefs.getThemeId(),prefs).bg);
        if (loaded) web.evaluateJavascript("window.WireApp && WireApp.applyTheme(" + new Bridge().getTheme() + ")", null);
    }
    public void run() {
        if(closed)return;resume();if(!saveBeforeRun())return;runRequested = true;
        if (loaded) web.evaluateJavascript("window.WireApp && WireApp.ready && WireHost.shouldRun() && WireApp.run(true)", null);
    }
    private boolean saveBeforeRun() {
        try{if(editorSaved.getAsBoolean())return true;}catch(RuntimeException error){android.util.Log.w("WireWorkspace","Editor save failed",error);}
        runRequested=false;
        String message="Запуск скасовано: код не збережено. Відкрийте змінені файли та виправте помилку збереження";
        Toast.makeText(activity,message,Toast.LENGTH_LONG).show();
        if(web!=null&&loaded)web.evaluateJavascript("window.WireApp && WireApp.saveFailed && WireApp.saveFailed("+JSONObject.quote(message)+")",null);
        return false;
    }
    public void codeSaved() {if (!closed&&loaded&&web!=null) web.evaluateJavascript("window.WireApp && WireApp.reloadPrograms()", null);}
    public void resume(){if(!closed)paused=false;}
    public void pause() {
        paused=true;runRequested=false;eventGeneration++;
        if (web != null && loaded) web.evaluateJavascript("window.WireApp && (WireApp.stop(),WireApp.save())", null);
        if (embedded != null) embedded.stopAll();
    }
    @Override public void close() {
        if(closed)return;closed=true;paused=true;runRequested=false;eventGeneration++;
        if(usb!=null)usb.closeManager();io.shutdownNow();
        if (embedded != null) embedded.close();
        if (web != null) {
            WebView closing=web;web=null;boolean wasLoaded=loaded;loaded=false;
            java.util.concurrent.atomic.AtomicBoolean destroyed=new java.util.concurrent.atomic.AtomicBoolean();
            Runnable destroy=()->{if(destroyed.compareAndSet(false,true)){closing.removeJavascriptInterface("WireHost");closing.stopLoading();closing.destroy();}};
            if(wasLoaded){closing.evaluateJavascript("window.WireApp && (WireApp.stop(),WireApp.save())",ignored->destroy.run());new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(destroy,2000);}
            else destroy.run();
        }
    }
}
