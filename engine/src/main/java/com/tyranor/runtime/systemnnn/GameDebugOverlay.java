package com.tyranor.runtime.systemnnn;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.widget.TextView;
import com.core.engine.R;

/** Passive status display for SystemNNN's TYN debug setting. */
final class GameDebugOverlay extends TextView {
    private static final String[] WAITS={
        "RUNNING","DRAW","OVERRAP","DIALOGUE","CHOICE","END","ERROR","MOVIE","PLACE","MODAL"
    };
    private static final int[] LAYER_SOURCES={
        R.string.engine_systemnnn_debug_files,R.string.engine_systemnnn_debug_loaded_save,R.string.engine_systemnnn_debug_exe
    };

    GameDebugOverlay(Context context) {
        super(context);
        int inset=Math.round(6*getResources().getDisplayMetrics().density);
        setPadding(inset,inset,inset,inset);
        setTextSize(11);
        setTextColor(Color.WHITE);
        setTypeface(Typeface.MONOSPACE);
        setBackgroundColor(0xcc151515);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setClickable(false);
        setFocusable(false);
        setVisibility(GONE);
    }

    void update(int[] s,String script,String bgm,int textWidth,int readingSize) {
        // wait, word, mode, canvas W/H, layers active/capacity/limit, save count/source,
        // MESSAGE write/read stride, active voice/SE counts, warnings, and frame period.
        if(s.length!=16)return;
        Context context=getContext();
        String wait=s[0]>=0&&s[0]<WAITS.length?WAITS[s[0]]:Integer.toString(s[0]);
        String source=s[9]>=0&&s[9]<LAYER_SOURCES.length?context.getString(LAYER_SOURCES[s[9]]):"—";
        String value=String.join("\n",
            context.getString(R.string.engine_systemnnn_debug_script,shortName(script),s[1],wait,s[2],s[15]),
            context.getString(R.string.engine_systemnnn_debug_layers,s[3],s[4],s[5],s[6],s[7]),
            context.getString(R.string.engine_systemnnn_debug_save,s[8],source,s[10],quantity(s[11]," B")),
            context.getString(R.string.engine_systemnnn_debug_text,quantity(textWidth," px"),readingSize),
            context.getString(R.string.engine_systemnnn_debug_audio,shortName(bgm),s[12],s[13],s[14]));
        if(!TextUtils.equals(getText(),value))setText(value);
        if(getVisibility()!=VISIBLE)setVisibility(VISIBLE);
    }

    private static String quantity(int value,String unit){return value>0?value+unit:"—";}
    private static String shortName(String name){
        if(name.isEmpty())return "—";
        int slash=Math.max(name.lastIndexOf('/'),name.lastIndexOf('\\'));
        String file=name.substring(slash+1).replace('\n',' ').replace('\t',' ');
        return file.length()<=24?file:file.substring(0,23)+"…";
    }
}
