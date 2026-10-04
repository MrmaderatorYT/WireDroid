package com.ccs.wiredroid.wire;

import java.io.IOException;
import java.util.Arrays;

/** STK500v1 flash upload with signature checks and complete page readback. */
public final class Stk500Uploader {
 public interface Transport {
  void write(byte[] data)throws IOException;
  int read(byte[] data,int offset,int length,int timeoutMs)throws IOException;
  void reset()throws IOException;
  void drain()throws IOException;
 }
 public interface Progress {void page(int completed,int total);}
 private final Transport transport;
 public Stk500Uploader(Transport transport){this.transport=transport;}
 public int upload(IntelHex.Image image,int maxFlash,int pageSize,byte[] expected,Progress progress)throws IOException{
  if(image.usedBytes>maxFlash||pageSize!=128||expected.length!=3||maxFlash>32768)throw new IOException("Прошивка не відповідає завантажувачу плати");
  transport.reset();boolean synced=false;IOException last=null;
  for(int attempt=0;attempt<10&&!synced;attempt++)try{checkCancel();transport.drain();command(new byte[]{0x30,0x20},0);synced=true;}catch(IOException e){last=e;}
  if(!synced)throw new IOException("Завантажувач не відповідає. Перевірте порт, профіль і bootloader.",last);
  byte[] actual=command(new byte[]{0x75,0x20},3);if(!Arrays.equals(actual,expected))throw new IOException("Підпис MCU не відповідає вибраній платі: "+hex(actual));
  command(new byte[]{0x50,0x20},0);int pages=(image.usedBytes+pageSize-1)/pageSize;
  try{
   for(int page=0;page<pages;page++){
    checkCancel();int offset=page*pageSize,size=Math.min(pageSize,maxFlash-offset);byte[] data=new byte[size];Arrays.fill(data,(byte)255);
    System.arraycopy(image.bytes,offset,data,0,Math.min(size,image.usedBytes-offset));address(offset);
    byte[] request=new byte[size+5];request[0]=0x64;request[1]=(byte)(size>>8);request[2]=(byte)size;request[3]=0x46;System.arraycopy(data,0,request,4,size);request[request.length-1]=0x20;command(request,0);
    address(offset);byte[] readback=command(new byte[]{0x74,(byte)(size>>8),(byte)size,0x46,0x20},size);
    if(!Arrays.equals(data,readback))throw new IOException("Перевірка flash не пройшла, адреса "+offset);
    if(progress!=null)progress.page(page+1,pages);
   }
  }finally{try{command(new byte[]{0x51,0x20},0);}catch(IOException ignored){}}
  return image.usedBytes;
 }
 private void address(int byteOffset)throws IOException{int word=byteOffset/2;command(new byte[]{0x55,(byte)word,(byte)(word>>8),0x20},0);}
 private byte[] command(byte[] request,int payload)throws IOException{
  checkCancel();transport.write(request);byte[] response=new byte[payload+2];int got=0;long deadline=System.nanoTime()+1_000_000_000L;
  while(got<response.length){checkCancel();int remaining=(int)Math.max(1,(deadline-System.nanoTime())/1_000_000L);if(System.nanoTime()>=deadline)throw new IOException("Таймаут завантажувача");int n=transport.read(response,got,response.length-got,remaining);if(n<0)throw new IOException("USB від’єднано під час завантаження");got+=n;}
  if(response[0]!=0x14||response[response.length-1]!=0x10)throw new IOException("Помилка відповіді STK500v1");return Arrays.copyOfRange(response,1,response.length-1);
 }
 private static void checkCancel()throws IOException{if(Thread.currentThread().isInterrupted())throw new IOException("Завантаження скасоване");}
 private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format("%02x",b&255));return out.toString();}
}
