package com.ccs.wiredroid.learn;

import java.util.ArrayList;
import java.util.List;

/**
 * Розділ матеріалів. Має двомовний заголовок та впорядкований список матеріалів.
 */
public final class Chapter {

    /** [0] = uk, [1] = en. */
    public final String[] title = new String[2];
    public final List<Lesson> materials = new ArrayList<>();

    public Chapter(String titleUk, String titleEn) {
        this.title[0] = titleUk;
        this.title[1] = titleEn;
    }

    public Chapter add(Lesson material) {
        materials.add(material);
        return this;
    }

    public String title(int lang) {
        return title[lang];
    }
}
