package com.ccs.wiredroid.maven;

/**
 * Utility class to modify pom.xml strings to add dependencies.
 */
public final class PomWriter {
    
    private PomWriter() {}

    /**
     * Inserts a dependency into the pom.xml source content.
     */
    public static String addDependency(String xml, String groupId, String artifactId, String version) {
        if (xml == null) {
            return "";
        }

        String depBlock = String.format(
                "        <dependency>\n" +
                "            <groupId>%s</groupId>\n" +
                "            <artifactId>%s</artifactId>\n" +
                "            <version>%s</version>\n" +
                "        </dependency>\n",
                groupId, artifactId, version
        );

        if (xml.contains("<dependencies>")) {
            int idx = xml.indexOf("<dependencies>");
            int insertPos = idx + "<dependencies>".length();
            return xml.substring(0, insertPos) + "\n" + depBlock + xml.substring(insertPos);
        } else {
            int idx = xml.lastIndexOf("</project>");
            if (idx >= 0) {
                String fullBlock = "    <dependencies>\n" + depBlock + "    </dependencies>\n";
                return xml.substring(0, idx) + fullBlock + xml.substring(idx);
            }
        }
        return xml;
    }
}
