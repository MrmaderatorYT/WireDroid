package com.ccs.wiredroid.wire;
import org.junit.Test;
import org.json.JSONArray;
import static org.junit.Assert.*;
public class EmbeddedPinsTest {
    @Test public void quotesCppPathsWithoutJsonSlashEscapes() {
        assertEquals("\"/sketches/board.ino\"",EmbeddedRunner.cppQuote("/sketches/board.ino"));
        assertEquals("\"pin/\\\"name\\\\\\012\"",EmbeddedRunner.cppQuote("pin/\"name\\\n"));
    }
    @Test public void mapsNumericAnalogAndNamedContacts() throws Exception {
        String h=EmbeddedRunner.pinHeader(new JSONArray("[{\"id\":\"connector56\",\"name\":\"D13\"},{\"id\":\"connector1\",\"name\":\"A0\"},{\"id\":\"c2\",\"name\":\"GPIO2 / SDA\"},{\"id\":\"named\",\"name\":\"P0.17\"}]"));
        assertTrue(h.contains("case 13:return 0"));assertTrue(h.contains("#define A0 1000"));assertTrue(h.contains("case 2:return 2"));assertTrue(h.contains("wire_equal(name,\"P0.17\")"));assertTrue(h.contains("wire_equal(name,\"connector56\")"));
    }
    @Test public void avoidsReservedMacrosAndAmbiguousDuplicateNumbers() throws Exception {
        String h=EmbeddedRunner.pinHeader(new JSONArray("[{\"id\":\"a\",\"name\":\"D1\"},{\"id\":\"b\",\"name\":\"GPIO1\"},{\"id\":\"c\",\"name\":\"INPUT\"}]"));
        assertEquals(1,h.split("case 1:",-1).length-1);assertFalse(h.contains("#define INPUT "));assertTrue(h.contains("wire_equal(name,\"b\")"));
    }
    @Test public void exportsEveryBoardHeaderForCrossCompilation() throws Exception {
        java.io.File asset=new java.io.File("src/main/assets/wiredroid/catalog.json");
        if(!asset.isFile())asset=new java.io.File("app/src/main/assets/wiredroid/catalog.json");
        JSONArray catalog=new JSONArray(new String(java.nio.file.Files.readAllBytes(asset.toPath()),java.nio.charset.StandardCharsets.UTF_8));
        java.io.File out=new java.io.File("build/wiredroid-board-headers");out.mkdirs();
        int count=0;
        for(int i=0;i<catalog.length();i++) {
            org.json.JSONObject part=catalog.getJSONObject(i);JSONArray pins=part.getJSONArray("pins");
            if(!part.optBoolean("board")||pins.length()==0)continue;
            assertTrue(part.getString("id"),pins.length()<=1024);
            String header=EmbeddedRunner.pinHeader(pins);
            for(int n=0;n<pins.length();n++)assertTrue(part.getString("id"),header.contains(org.json.JSONObject.quote(pins.getJSONObject(n).getString("id"))));
            java.nio.file.Files.write(new java.io.File(out,"board-"+count+".h").toPath(),header.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String code="#include \"board-"+count+".h\"\nvoid setup(){pinMode("+org.json.JSONObject.quote(pins.getJSONObject(0).getString("id"))+",OUTPUT);}\nvoid loop(){delay(10);}\n";
            java.nio.file.Files.write(new java.io.File(out,"board-"+count+".cpp").toPath(),code.getBytes(java.nio.charset.StandardCharsets.UTF_8));count++;
        }
        assertEquals(327,count);
    }
}
