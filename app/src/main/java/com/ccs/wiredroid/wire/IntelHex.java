package com.ccs.wiredroid.wire;

import java.io.IOException;
import java.util.Arrays;

/** Strict Intel HEX parser shared by MCU import and physical upload. */
public final class IntelHex {
    public static final class Image {
        public final byte[] bytes;
        public final int usedBytes;
        Image(byte[] bytes,int used){this.bytes=bytes;usedBytes=used;}
    }
    private IntelHex(){}
    public static Image parse(String raw,int maxBytes)throws IOException{
        if(maxBytes<=0||maxBytes>1024*1024||raw==null||raw.length()>8*1024*1024)throw new IOException("Некоректний розмір прошивки");
        byte[] image=new byte[maxBytes];Arrays.fill(image,(byte)255);boolean[] set=new boolean[maxBytes];
        long base=0;int highest=0,count=0;boolean eof=false;
        for(String input:raw.split("\\r?\\n")){
            String line=input.trim();if(line.isEmpty())continue;if(eof)throw new IOException("Дані після EOF у HEX");
            if(++count>65536||!line.startsWith(":")||line.length()<11||(line.length()&1)==0)throw new IOException("Некоректний запис HEX, рядок "+count);
            int n=number(line,1);if(line.length()!=11+n*2)throw new IOException("Некоректна довжина HEX, рядок "+count);
            int checksum=0;for(int i=1;i<line.length();i+=2)checksum+=number(line,i);if((checksum&255)!=0)throw new IOException("Помилка checksum HEX, рядок "+count);
            int offset=(number(line,3)<<8)|number(line,5),type=number(line,7);
            if(type==0){long start=base+offset;if(start<0||start+n>maxBytes)throw new IOException("HEX перевищує пам’ять вибраної плати");
                for(int i=0;i<n;i++){int address=(int)start+i;byte value=(byte)number(line,9+i*2);if(set[address]&&image[address]!=value)throw new IOException("Суперечливі дані HEX за адресою "+address);image[address]=value;set[address]=true;}
                highest=Math.max(highest,(int)start+n);
            }else if(type==1){if(n!=0||offset!=0)throw new IOException("Некоректний EOF HEX");eof=true;}
            else if(type==2||type==4){if(n!=2||offset!=0)throw new IOException("Некоректна адреса HEX");base=((number(line,9)<<8)|number(line,11))*(type==2?16L:65536L);}
            else if(type==3||type==5){if(n!=4||offset!=0)throw new IOException("Некоректна стартова адреса HEX");}
            else throw new IOException("Непідтримуваний тип запису HEX: "+type);
        }
        if(!eof||highest==0)throw new IOException("HEX порожній або не містить EOF");
        return new Image(Arrays.copyOf(image,highest),highest);
    }
    private static int number(String line,int start)throws IOException{try{return Integer.parseInt(line.substring(start,start+2),16);}catch(Exception e){throw new IOException("Некоректне шістнадцяткове число HEX");}}
}
