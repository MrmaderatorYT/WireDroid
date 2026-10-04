package com.ccs.wiredroid.wire;

import android.app.PendingIntent;
import android.content.*;
import android.hardware.usb.*;
import android.os.Build;
import android.util.Base64;
import com.hoho.android.usbserial.driver.*;
import java.io.IOException;
import java.util.*;
import org.json.*;

/** Android USB permission/discovery, upstream serial drivers, and verified AVR upload. */
public final class WireUsbManager {
 private final Context context;private final UsbManager manager;private final String permissionAction;
 private UsbSerialPort port;private UsbDeviceConnection connection;private String deviceName="";private int baudRate;
 private BoardRegistry registry;
 private volatile String lastError="";private volatile Thread uploader;
 private final BroadcastReceiver receiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent intent){
  if(UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())){UsbDevice device=intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);if(device!=null&&device.getDeviceName().equals(deviceName)){cancelUpload();close();lastError="USB-пристрій від’єднано";}}
  else if(permissionAction.equals(intent.getAction())&&!intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false))lastError="Дозвіл USB не надано";
 }};
 public WireUsbManager(Context context){this.context=context.getApplicationContext();manager=(UsbManager)this.context.getSystemService(Context.USB_SERVICE);permissionAction=this.context.getPackageName()+".WIRE_USB_PERMISSION";
  try{registry=BoardRegistry.load(this.context);}catch(Exception e){lastError=e.getMessage();}
  IntentFilter filter=new IntentFilter(permissionAction);filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);if(Build.VERSION.SDK_INT>=33)this.context.registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED);else this.context.registerReceiver(receiver,filter);
 }
 public String enumerate(){JSONArray devices=new JSONArray();if(manager==null)return devices.toString();
  List<UsbDevice> sorted=new ArrayList<>(manager.getDeviceList().values());sorted.sort(Comparator.comparing(UsbDevice::getDeviceName));
  for(UsbDevice device:sorted)try{UsbSerialDriver driver=UsbSerialProber.getDefaultProber().probeDevice(device);devices.put(new JSONObject().put("name",device.getDeviceName()).put("vid",device.getVendorId()).put("pid",device.getProductId()).put("driver",driver==null?"unsupported":driver.getClass().getSimpleName()).put("ports",driver==null?0:driver.getPorts().size()).put("permission",manager.hasPermission(device)).put("profiles",registry==null?new JSONArray():registry.usbCandidates(device.getVendorId(),device.getProductId())).put("manufacturer",device.getManufacturerName()).put("product",device.getProductName()));}catch(Exception e){lastError=e.getMessage();}return devices.toString();
 }
 private UsbDevice device(String name)throws IOException{if(manager==null)throw new IOException("USB Host недоступний");UsbDevice device=manager.getDeviceList().get(name);if(device==null)throw new IOException("USB-пристрій від’єднано");return device;}
 public String requestPermission(String name){try{UsbDevice device=device(name);if(manager.hasPermission(device))return "";Intent intent=new Intent(permissionAction).setPackage(context.getPackageName());manager.requestPermission(device,PendingIntent.getBroadcast(context,name.hashCode(),intent,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT));return "";}catch(Exception e){return error(e);}}
 public synchronized String open(String name,int portIndex,int baud){try{if(uploader!=null)throw new IOException("Завантаження вже виконується");close();if(baud<300||baud>4000000)throw new IOException("Некоректна швидкість Serial");UsbDevice device=device(name);if(!manager.hasPermission(device))throw new IOException("Спочатку надайте дозвіл Android USB");UsbSerialDriver driver=UsbSerialProber.getDefaultProber().probeDevice(device);if(driver==null||portIndex<0||portIndex>=driver.getPorts().size())throw new IOException("Цей USB Serial порт не підтримується");connection=manager.openDevice(device);if(connection==null)throw new IOException("Не вдалося відкрити USB-пристрій");port=driver.getPorts().get(portIndex);port.open(connection);port.setParameters(baud,8,UsbSerialPort.STOPBITS_1,UsbSerialPort.PARITY_NONE);deviceName=name;baudRate=baud;lastError="";return "";}catch(Exception e){close();return error(e);}}
 public synchronized String read(int maxBytes){if(port==null||uploader!=null)return "";try{int limit=Math.max(1,Math.min(65536,maxBytes));byte[] bytes=new byte[limit];int n=port.read(bytes,50);return n>0?Base64.encodeToString(Arrays.copyOf(bytes,n),Base64.NO_WRAP):"";}catch(Exception e){lastError=e.getMessage();close();return "";}}
 public synchronized String write(String base64){try{if(port==null)throw new IOException("Serial порт не відкритий");if(uploader!=null)throw new IOException("Serial зайнятий завантаженням");if(base64==null||base64.length()>128*1024)throw new IOException("Повідомлення Serial завелике");port.write(Base64.decode(base64,Base64.DEFAULT),1000);return "";}catch(Exception e){return error(e);}}
 public synchronized JSONObject status(){try{return new JSONObject().put("connected",port!=null).put("device",deviceName).put("baud",baudRate).put("uploading",uploader!=null).put("error",lastError);}catch(JSONException impossible){throw new IllegalStateException(impossible);}}
 public JSONObject upload(String raw,JSONObject target)throws Exception{
  final UsbSerialPort active;
  synchronized(this){if(port==null)throw new IOException("Serial порт не відкритий");if(uploader!=null)throw new IOException("Завантаження вже виконується");active=port;uploader=Thread.currentThread();}
  try{
   JSONObject upload=target.getJSONObject("upload");if(!"stk500v1".equals(upload.getString("protocol")))throw new IOException("Завантажувач цього профілю ще не підтримується");
   int max=upload.getInt("maxFlash");IntelHex.Image image=IntelHex.parse(raw,max);JSONArray signature=upload.getJSONArray("signature");byte[] expected=new byte[signature.length()];for(int i=0;i<expected.length;i++)expected[i]=(byte)signature.getInt(i);
   active.setParameters(upload.getInt("baud"),8,UsbSerialPort.STOPBITS_1,UsbSerialPort.PARITY_NONE);
   Stk500Uploader transport=new Stk500Uploader(new Stk500Uploader.Transport(){
    public void write(byte[] bytes)throws IOException{active.write(bytes,1000);}
    public int read(byte[] bytes,int offset,int length,int timeout)throws IOException{byte[] temp=new byte[length];int n=active.read(temp,timeout);if(n>0)System.arraycopy(temp,0,bytes,offset,n);return n;}
    public void reset()throws IOException{try{active.setDTR(false);active.setRTS(false);Thread.sleep(100);active.setDTR(true);active.setRTS(true);Thread.sleep(150);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Завантаження скасоване");}}
    public void drain()throws IOException{try{active.purgeHwBuffers(false,true);}catch(UnsupportedOperationException ignored){byte[] buffer=new byte[1024];for(int i=0;i<4&&active.read(buffer,20)>0;i++);}}
   });
   int written=transport.upload(image,max,upload.getInt("pageSize"),expected,null);lastError="";return new JSONObject().put("verified",true).put("bytes",written).put("profile",target.getString("id")).put("device",deviceName);
  }catch(Exception e){lastError=e.getMessage();throw e;}finally{synchronized(this){uploader=null;try{if(port==active)active.setParameters(baudRate,8,UsbSerialPort.STOPBITS_1,UsbSerialPort.PARITY_NONE);}catch(Exception ignored){close();}}}
 }
 public void cancelUpload(){Thread current=uploader;if(current!=null)current.interrupt();}
 public synchronized String close(){cancelUpload();UsbSerialPort old=port;port=null;deviceName="";baudRate=0;try{if(old!=null)old.close();}catch(Exception ignored){}if(connection!=null){connection.close();connection=null;}return "";}
 public void closeManager(){close();try{context.unregisterReceiver(receiver);}catch(IllegalArgumentException ignored){}}
 private String error(Exception e){lastError=e.getMessage()==null?e.toString():e.getMessage();return lastError;}
}
