package com.ccs.wiredroid.wire;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.*;
import android.widget.*;
import androidx.drawerlayout.widget.DrawerLayout;
import com.ccs.wiredroid.util.AppTheme;
import java.io.File;
import java.util.function.Consumer;

/** IDE tool window: right drawer on phones, docked beside the full editor on wide screens. */
public final class WireDrawerController implements AutoCloseable {
    private final Activity activity;
    private final DrawerLayout drawers;
    private final File root;
    private final Consumer<File> openCode;
    private final Runnable saveCode;
    private final java.util.function.BooleanSupplier editorSaved;
    private final WireWorkspace.DiagnosticOpener openDiagnostic;
    private final LinearLayout row;
    private final FrameLayout dock, sliding, rail;
    private final TextView railLabel;
    private LinearLayout panel;
    private View separator;
    private TextView title, pin, close;
    private WireWorkspace workspace;
    private AppTheme theme;
    private boolean pinned,visible;

    public WireDrawerController(Activity activity, DrawerLayout drawers, File root, AppTheme theme,
                                Consumer<File> openCode, Runnable saveCode) {
        this(activity,drawers,root,theme,openCode,saveCode,(file,line,column)->openCode.accept(file));
    }
    public WireDrawerController(Activity activity, DrawerLayout drawers, File root, AppTheme theme,
                                Consumer<File> openCode, Runnable saveCode,WireWorkspace.DiagnosticOpener openDiagnostic) {
        this(activity,drawers,root,theme,openCode,saveCode,openDiagnostic,()->{saveCode.run();return true;});
    }
    public WireDrawerController(Activity activity,DrawerLayout drawers,File root,AppTheme theme,
                                Consumer<File> openCode,Runnable saveCode,WireWorkspace.DiagnosticOpener openDiagnostic,
                                java.util.function.BooleanSupplier editorSaved) {
        this.editorSaved=editorSaved;
        this.activity=activity;this.drawers=drawers;this.root=root;this.theme=theme;
        this.openCode=openCode;this.saveCode=saveCode;this.openDiagnostic=openDiagnostic;
        // Keep the complete existing IDE view tree; add a tool window alongside it.
        View editor=drawers.getChildAt(0);int index=drawers.indexOfChild(editor);drawers.removeView(editor);
        row=new LinearLayout(activity);row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(editor,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.MATCH_PARENT,1));
        dock=new FrameLayout(activity);dock.setVisibility(View.GONE);
        row.addView(dock,new LinearLayout.LayoutParams(panelWidth(),ViewGroup.LayoutParams.MATCH_PARENT));
        rail=new FrameLayout(activity);rail.setContentDescription("Плати та з’єднання · права панель");
        railLabel=new TextView(activity);railLabel.setText("⌁  Плати");railLabel.setTextSize(11);railLabel.setGravity(Gravity.CENTER);railLabel.setRotation(-90);
        FrameLayout.LayoutParams railText=new FrameLayout.LayoutParams(dp(120),dp(28),Gravity.TOP|Gravity.CENTER_HORIZONTAL);railText.topMargin=dp(68);rail.addView(railLabel,railText);
        rail.setOnClickListener(v->{if(isVisible())hide();else show(false);});
        row.addView(rail,new LinearLayout.LayoutParams(dp(28),ViewGroup.LayoutParams.MATCH_PARENT));
        drawers.addView(row,index,new DrawerLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
        sliding=new FrameLayout(activity);sliding.setContentDescription("Конструктор плат");
        DrawerLayout.LayoutParams lp=new DrawerLayout.LayoutParams(panelWidth(),ViewGroup.LayoutParams.MATCH_PARENT);lp.gravity=Gravity.END;
        drawers.addView(sliding,lp);
        drawers.addDrawerListener(new DrawerLayout.SimpleDrawerListener(){
            @Override public void onDrawerOpened(View view){if(view==sliding&&!pinned){visible=true;if(workspace!=null)workspace.resume();}}
            @Override public void onDrawerClosed(View view){if(view==sliding&&!pinned){visible=false;if(workspace!=null)workspace.pause();applyTheme(WireDrawerController.this.theme);}}
        });
        applyTheme(theme);
    }
    private int dp(float n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
    private int widthDp(){return activity.getResources().getConfiguration().screenWidthDp;}
    private int panelWidth(){return dp(Math.min(720,Math.max(280,widthDp()>=840?widthDp()*.46f:widthDp()-48)));}
    public boolean canPin(){return widthDp()>=840;}
    private TextView tool(String text,String description,Runnable action){
        TextView view=new TextView(activity);view.setText(text);view.setTextSize(13);view.setGravity(Gravity.CENTER);
        view.setPadding(dp(10),0,dp(10),0);view.setContentDescription(description);view.setOnClickListener(v->action.run());return view;
    }
    private boolean ensure(){
        if(workspace!=null)return true;
        try{
            workspace=new WireWorkspace(activity,root,file->{
                workspace.pause();saveCode.run();openCode.accept(file);
                if(!pinned)hide();else workspace.resume();
            },saveCode,(file,line,column)->{
                workspace.pause();saveCode.run();openDiagnostic.open(file,line,column);
                if(!pinned)hide();else workspace.resume();
            },editorSaved);
            panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);
            LinearLayout header=new LinearLayout(activity);header.setGravity(Gravity.CENTER_VERTICAL);
            title=tool("Плати та з’єднання","Конструктор схеми",()->{});title.setTypeface(null,Typeface.BOLD);
            header.addView(title,new LinearLayout.LayoutParams(0,dp(44),1));
            pin=tool("Закріпити","Закріпити конструктор справа від коду",()->setPinned(!pinned));
            header.addView(pin,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,dp(44)));
            close=tool("×","Закрити конструктор",this::hide);header.addView(close,new LinearLayout.LayoutParams(dp(40),dp(44)));
            panel.addView(header);separator=new View(activity);panel.addView(separator,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(1)));
            panel.addView(workspace.getView(),new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1));
            sliding.addView(panel,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
            applyTheme(theme);return true;
        }catch(Exception e){Toast.makeText(activity,e.getMessage(),Toast.LENGTH_LONG).show();return false;}
    }
    public void show(boolean run){
        if(!ensure())return;visible=true;
        if(canPin() && activity.getPreferences(Activity.MODE_PRIVATE).getBoolean("wire_pinned",true))setPinned(true);
        else if(pinned)dock.setVisibility(View.VISIBLE);
        else drawers.openDrawer(sliding);
        workspace.resume();applyTheme(theme);if(run)workspace.run();
    }
    public void setPinned(boolean value){
        if(value&&!canPin())return;if(!ensure())return;
        // Move only the tool window; the IDE's editor panes and tabs stay intact.
        drawers.closeDrawer(sliding,false);
        ViewGroup parent=(ViewGroup)panel.getParent();if(parent!=null)parent.removeView(panel);
        pinned=value;visible=true;
        if(value){dock.addView(panel);dock.setVisibility(View.VISIBLE);}
        else{dock.setVisibility(View.GONE);sliding.addView(panel);drawers.openDrawer(sliding);}
        workspace.resume();activity.getPreferences(Activity.MODE_PRIVATE).edit().putBoolean("wire_pinned",value).apply();applyTheme(theme);
    }
    public boolean isVisible(){return pinned?dock.getVisibility()==View.VISIBLE:visible||drawers.isDrawerVisible(sliding);}
    public boolean closeDrawer(){if(!pinned&&isVisible()){hide();return true;}return false;}
    public void hide(){visible=false;if(workspace!=null)workspace.pause();dock.setVisibility(View.GONE);drawers.closeDrawer(sliding);applyTheme(theme);}
    public void pause(){if(workspace!=null)workspace.pause();}
    public void resume(){if(workspace!=null&&isVisible())workspace.resume();}
    public void codeSaved(){if(workspace!=null)workspace.codeSaved();}
    public void applyTheme(AppTheme theme){
        this.theme=theme;row.setBackgroundColor(theme.bg);rail.setBackgroundColor(theme.toolbar);
        railLabel.setTextColor(visible?theme.accent:theme.textDim);dock.setBackgroundColor(theme.toolbar);sliding.setBackgroundColor(theme.toolbar);
        if(panel!=null){panel.setBackgroundColor(theme.toolbar);title.setTextColor(theme.text);pin.setTextColor(theme.textDim);close.setTextColor(theme.textDim);
            separator.setBackgroundColor(theme.separator);
            pin.setText(pinned?"Відкріпити":"Закріпити");pin.setVisibility(canPin()?View.VISIBLE:View.GONE);workspace.applyTheme();}
    }
    public android.webkit.WebView getWebView(){return workspace==null?null:workspace.getView();}
    @Override public void close(){if(workspace!=null)workspace.close();}
}
