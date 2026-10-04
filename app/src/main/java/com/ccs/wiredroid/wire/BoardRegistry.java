package com.ccs.wiredroid.wire;
import android.content.Context;
import java.io.*;
import org.json.*;
/** Independent image/GPIO/build/MCU/upload levels; unknown hardware stays unknown. */
public final class BoardRegistry {
 private final JSONObject data;
 public BoardRegistry(String raw)throws Exception{data=new JSONObject(raw);if(data.getInt("version")!=1)throw new IOException("Невідома версія реєстру плат");}
 public static BoardRegistry load(Context context)throws Exception{try(InputStream in=context.getAssets().open("wiredroid/boards.json")){return new BoardRegistry(WireProject.read(in,2*1024*1024));}}
 public String json(){return data.toString();}
 public JSONArray all()throws JSONException{return data.getJSONArray("boards");}
 public JSONObject profile(String id)throws Exception{JSONArray targets=data.getJSONArray("targets");for(int i=0;i<targets.length();i++)if(targets.getJSONObject(i).getString("id").equals(id))return targets.getJSONObject(i);throw new IOException("Немає підтвердженого профілю: "+id);}
 public JSONArray usbCandidates(int vendor,int product)throws JSONException{JSONArray result=new JSONArray(),targets=data.getJSONArray("targets");for(int i=0;i<targets.length();i++){JSONObject target=targets.getJSONObject(i);JSONArray ids=target.optJSONArray("usbIds");if(ids==null)continue;for(int j=0;j<ids.length();j++)if(ids.getJSONObject(j).getInt("vid")==vendor&&ids.getJSONObject(j).getInt("pid")==product){result.put(target.getString("id"));break;}}return result;}
 public JSONObject board(String id)throws Exception{JSONArray boards=all();for(int i=0;i<boards.length();i++)if(boards.getJSONObject(i).getString("catalogId").equals(id))return boards.getJSONObject(i);throw new IOException("Плата відсутня у реєстрі: "+id);}
}
