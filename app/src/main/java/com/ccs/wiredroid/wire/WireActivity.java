package com.ccs.wiredroid.wire;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.ccs.wiredroid.ui.MainActivity;
import com.ccs.wiredroid.ui.FileOpenActivity;
import java.io.File;

/** Compatibility entry point; ordinary project navigation opens the full IDE. */
public final class WireActivity extends AppCompatActivity {
    private WireWorkspace workspace;
    public static void launch(Context context, File root, boolean run) {
        if (!WireProject.isCircuit(root)) {Toast.makeText(context,"Створіть embedded-проєкт WireDroid",Toast.LENGTH_LONG).show();return;}
        new com.ccs.wiredroid.util.AppPreferences(context).setProjectRoot(root.getAbsolutePath());
        context.startActivity(new Intent(context,MainActivity.class).putExtra("wire_show",run).putExtra("wire_run",run));
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            File root = new File(getIntent().getStringExtra("wire_root"));
            workspace = new WireWorkspace(this,root,file -> startActivity(new Intent(this,MainActivity.class)
                .putExtra(FileOpenActivity.EXTRA_OPEN_FILE,file.getPath())),()->{});
            setContentView(workspace.getView());com.ccs.wiredroid.util.FullScreenHelper.enable(this);
            if (getIntent().getBooleanExtra("wire_run",false)) workspace.run();
        }catch(Exception e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();finish();}
    }
    @Override protected void onPause(){if(workspace!=null)workspace.pause();super.onPause();}
    @Override protected void onDestroy(){if(workspace!=null)workspace.close();super.onDestroy();}
}
