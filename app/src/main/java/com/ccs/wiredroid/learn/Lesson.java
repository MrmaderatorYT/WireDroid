package com.ccs.wiredroid.learn;

import java.util.List;

/**
 * Матеріал. Має ідентифікатор (унікальний у межах розділу), заголовок обома мовами
 * та контент, який за потреби завантажується з assets.
 */
public final class Lesson {

    public final String id;
    /** [0] = uk, [1] = en. */
    public final String[] title = new String[2];
    /** [0] = uk, [1] = en. */
    private final List<LessonBlock>[] content;
    private final ContentLoader contentLoader;

    @SuppressWarnings("unchecked")
    public Lesson(String id, String titleUk, String titleEn,
                    List<LessonBlock> contentUk, List<LessonBlock> contentEn) {
        this.id = id;
        this.title[0] = titleUk;
        this.title[1] = titleEn;
        this.content = new List[] { contentUk, contentEn };
        this.contentLoader = null;
    }

    /** Створює опис уроку без завантаження його тексту в пам'ять. */
    @SuppressWarnings("unchecked")
    Lesson(String id, String titleUk, String titleEn, ContentLoader contentLoader) {
        this.id = id;
        this.title[0] = titleUk;
        this.title[1] = titleEn;
        this.content = new List[2];
        this.contentLoader = contentLoader;
    }

    public String title(int lang) {
        return title[lang];
    }

    public synchronized List<LessonBlock> content(int lang) {
        if (lang != CourseRegistry.LANG_UK && lang != CourseRegistry.LANG_EN) {
            throw new IllegalArgumentException("Unsupported lesson language: " + lang);
        }
        if (content[lang] == null) {
            if (contentLoader == null) {
                throw new IllegalStateException("Lesson content is missing: " + id);
            }
            content[lang] = contentLoader.load(lang);
        }
        return content[lang];
    }

    interface ContentLoader {
        List<LessonBlock> load(int language);
    }
}
