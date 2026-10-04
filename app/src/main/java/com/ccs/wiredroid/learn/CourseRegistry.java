package com.ccs.wiredroid.learn;

import android.content.Context;

import java.util.List;

/**
 * Реєстр матеріалів. Опис курсу читається з assets, а текст уроку — лише під час відкриття.
 *
 * <p>Мова — глобальна для всього центру матеріалів (uk/en), зберігається тут.</p>
 */
public final class CourseRegistry {

    /** Українська. */
    public static final int LANG_UK = 0;
    /** Англійська. */
    public static final int LANG_EN = 1;

    private static volatile CourseRegistry instance;

    private final List<Course> courses;
    private volatile int language = LANG_UK;

    private CourseRegistry(Context context) {
        courses = new CourseAssetRepository(context).loadCatalog();
    }

    // ── Singleton ────────────────────────────────────────────────────────

    public static CourseRegistry getInstance(Context context) {
        if (instance == null) {
            synchronized (CourseRegistry.class) {
                if (instance == null) instance = new CourseRegistry(context.getApplicationContext());
            }
        }
        return instance;
    }

    public List<Course> getCourses() {
        return courses;
    }

    public Course getCourse(String id) {
        for (Course s : courses) {
            if (s.id.equals(id)) return s;
        }
        return null;
    }

    public int getLanguage() {
        return language;
    }

    public void setLanguage(int lang) {
        if (lang == LANG_UK || lang == LANG_EN) {
            this.language = lang;
        }
    }

    public boolean isEnglish() {
        return language == LANG_EN;
    }
}
