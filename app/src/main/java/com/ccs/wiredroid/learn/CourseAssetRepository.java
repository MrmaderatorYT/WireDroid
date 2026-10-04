package com.ccs.wiredroid.learn;

import android.content.Context;
import android.content.res.AssetManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Reads the editable course catalog from {@code assets/courses}. */
final class CourseAssetRepository {

    private static final String ROOT = "courses/";

    private final AssetManager assets;

    CourseAssetRepository(Context context) {
        assets = context.getApplicationContext().getAssets();
    }

    List<Course> loadCatalog() {
        try {
            JSONArray items = new JSONObject(readAsset(ROOT + "index.json")).getJSONArray("courses");
            List<Course> courses = new ArrayList<>(items.length());
            for (int i = 0; i < items.length(); i++) {
                courses.add(parseCourse(items.getJSONObject(i)));
            }
            return Collections.unmodifiableList(courses);
        } catch (IOException | JSONException e) {
            throw new IllegalStateException("Cannot load bundled course catalog", e);
        }
    }

    private Course parseCourse(JSONObject source) throws JSONException {
        JSONObject title = source.getJSONObject("title");
        JSONObject description = source.getJSONObject("description");
        Course course = new Course(
                source.getString("id"),
                localized(title, CourseRegistry.LANG_UK),
                localized(title, CourseRegistry.LANG_EN),
                localized(description, CourseRegistry.LANG_UK),
                localized(description, CourseRegistry.LANG_EN));

        JSONArray chapters = source.getJSONArray("chapters");
        for (int chapterIndex = 0; chapterIndex < chapters.length(); chapterIndex++) {
            JSONObject chapterSource = chapters.getJSONObject(chapterIndex);
            JSONObject chapterTitle = chapterSource.getJSONObject("title");
            Chapter chapter = new Chapter(
                    localized(chapterTitle, CourseRegistry.LANG_UK),
                    localized(chapterTitle, CourseRegistry.LANG_EN));
            JSONArray lessons = chapterSource.getJSONArray("lessons");
            for (int lessonIndex = 0; lessonIndex < lessons.length(); lessonIndex++) {
                JSONObject lessonSource = lessons.getJSONObject(lessonIndex);
                JSONObject lessonTitle = lessonSource.getJSONObject("title");
                String lessonId = lessonSource.getString("id");
                chapter.add(new Lesson(
                        lessonId,
                        localized(lessonTitle, CourseRegistry.LANG_UK),
                        localized(lessonTitle, CourseRegistry.LANG_EN),
                        language -> loadLesson(course.id, lessonId, language)));
            }
            course.add(chapter);
        }
        return course;
    }

    private List<LessonBlock> loadLesson(String courseId, String lessonId, int language) {
        try {
            JSONObject content = new JSONObject(readAsset(
                    ROOT + "lessons/" + courseId + "/" + lessonId + ".json"))
                    .getJSONObject("content");
            JSONArray blocks = content.getJSONArray(language == CourseRegistry.LANG_EN ? "en" : "uk");
            List<LessonBlock> result = new ArrayList<>(blocks.length());
            for (int i = 0; i < blocks.length(); i++) {
                result.add(parseBlock(blocks.getJSONObject(i)));
            }
            return Collections.unmodifiableList(result);
        } catch (IOException | JSONException e) {
            throw new IllegalStateException("Cannot load lesson " + courseId + "/" + lessonId, e);
        }
    }

    private static LessonBlock parseBlock(JSONObject source) throws JSONException {
        String typeName = source.getString("type");
        int type = blockType(typeName);
        String text = source.getString("text");
        String tableHeader = source.optString("tableHeader", null);
        String runModeName = source.optString("runMode", "none");
        int runMode = runMode(runModeName);
        String executionText = source.optString("executionText", null);
        if (runMode == LessonBlock.RUN_NONE) executionText = null;
        return LessonBlock.fromAsset(type, text, tableHeader, runMode, executionText);
    }

    private static String localized(JSONObject values, int language) throws JSONException {
        return values.getString(language == CourseRegistry.LANG_EN ? "en" : "uk");
    }

    private static int blockType(String name) throws JSONException {
        switch (name) {
            case "heading": return LessonBlock.HEADING;
            case "paragraph": return LessonBlock.PARAGRAPH;
            case "code": return LessonBlock.CODE;
            case "list": return LessonBlock.LIST;
            case "note": return LessonBlock.NOTE;
            case "warning": return LessonBlock.WARNING;
            case "table": return LessonBlock.TABLE;
            default: throw new JSONException("Unknown lesson block type: " + name);
        }
    }

    private static int runMode(String name) throws JSONException {
        switch (name) {
            case "none": return LessonBlock.RUN_NONE;
            case "java_statements": return LessonBlock.RUN_JAVA_STATEMENTS;
            case "java_source": return LessonBlock.RUN_JAVA_SOURCE;
            default: throw new JSONException("Unknown runnable code mode: " + name);
        }
    }

    private String readAsset(String path) throws IOException {
        try (InputStream input = assets.open(path); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
}
