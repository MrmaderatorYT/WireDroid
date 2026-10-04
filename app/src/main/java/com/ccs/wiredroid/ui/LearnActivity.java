package com.ccs.wiredroid.ui;
import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.AppPreferences;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.FullScreenHelper;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.ccs.wiredroid.learn.Course;
import com.ccs.wiredroid.learn.CourseOutlineAdapter;
import com.ccs.wiredroid.learn.CourseRegistry;
import com.ccs.wiredroid.learn.LessonActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * Навчальний центр: список курсів (Spinner) з перемикачем uk/en.
 * Використовує нативні моделі {@link CourseRegistry} (без HTML/WebView).
 */
public class LearnActivity extends AppCompatActivity {

    private AppPreferences appPrefs;
    private AppTheme theme;
    private RecyclerView rvChapters;
    private Spinner spinnerCourse;
    private TextView btnLanguage;
    private TextView tvDescription;
    private ArrayAdapter<String> courseAdapter;

    private CourseRegistry courseRegistry;
    private List<Course> courses;
    private int currentCourseIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        appPrefs = new AppPreferences(this);
        theme = AppTheme.byId(appPrefs.getThemeId(), appPrefs);
        setTheme(theme.dark ? R.style.Theme_WireDroid : R.style.Theme_WireDroid_Light);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_learn);
        FullScreenHelper.enable(this);

        Toolbar toolbar = findViewById(R.id.learnToolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setBackgroundColor(theme.toolbar);

        View root = findViewById(R.id.learnRoot);
        if (root != null) root.setBackgroundColor(theme.bg);

        View toolbarSep = findViewById(R.id.learnToolbarSep);
        if (toolbarSep != null) toolbarSep.setBackgroundColor(theme.separator);

        View descContainer = findViewById(R.id.courseDescContainer);
        if (descContainer != null) descContainer.setBackgroundColor(theme.consoleBg);

        View descDivider = findViewById(R.id.courseDescDivider);
        if (descDivider != null) descDivider.setBackgroundColor(theme.separator);

        spinnerCourse = findViewById(R.id.spinnerCourse);
        btnLanguage = findViewById(R.id.btnLanguage);
        tvDescription = findViewById(R.id.tvCourseDescription);
        if (tvDescription != null) {
            tvDescription.setTextColor(theme.textDim);
            tvDescription.setBackgroundColor(theme.consoleBg);
        }

        rvChapters = findViewById(R.id.rvChapters);
        rvChapters.setLayoutManager(new LinearLayoutManager(this));
        rvChapters.setBackgroundColor(theme.bg);
        findViewById(android.R.id.content).setBackgroundColor(theme.bg);

        // Перемикач мови
        btnLanguage.setTextColor(theme.accent);
        btnLanguage.setOnClickListener(v -> {
            CourseRegistry reg = courseRegistry;
            if (reg == null) return;
            reg.setLanguage(reg.isEnglish() ? CourseRegistry.LANG_UK : CourseRegistry.LANG_EN);
            updateLanguageButton();
            rebuildSpinnerTitles();
            showCourse();
        });

        loadCatalog();
    }

    /**
     * Каталог читається з assets і розбирається як JSON — на головному потоці це
     * тримало б екран порожнім до кінця розбору, тому список курсів з'являється
     * щойно каталог готовий.
     */
    private void loadCatalog() {
        new Thread(() -> {
            CourseRegistry registry = CourseRegistry.getInstance(getApplicationContext());
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                bindCatalog(registry);
            });
        }, "course-catalog").start();
    }

    private void bindCatalog(CourseRegistry registry) {
        courseRegistry = registry;
        courses = registry.getCourses();

        // Spinner курсів
        List<String> titles = new ArrayList<>();
        int lang = registry.getLanguage();
        for (Course c : courses) titles.add(c.title(lang));
        courseAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, titles) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                if (v instanceof TextView) {
                    ((TextView) v).setTextColor(theme.text);
                    ((TextView) v).setTextSize(14);
                }
                return v;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                View v = super.getDropDownView(position, convertView, parent);
                if (v instanceof TextView) {
                    ((TextView) v).setTextColor(theme.text);
                    ((TextView) v).setBackgroundColor(theme.toolbar);
                    int pad = (int) (12 * getResources().getDisplayMetrics().density);
                    v.setPadding(pad, pad, pad, pad);
                }
                return v;
            }
        };
        courseAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerCourse.setAdapter(courseAdapter);
        spinnerCourse.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                currentCourseIndex = pos;
                showCourse();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        updateLanguageButton();
    }

    private void updateLanguageButton() {
        btnLanguage.setText(courseRegistry.isEnglish() ? "EN" : "UK");
        btnLanguage.setTextColor(theme.accent);
    }

    private void rebuildSpinnerTitles() {
        int lang = courseRegistry.getLanguage();
        courseAdapter.clear();
        for (Course c : courses) courseAdapter.add(c.title(lang));
        courseAdapter.notifyDataSetChanged();
        if (currentCourseIndex < courseAdapter.getCount()) {
            spinnerCourse.setSelection(currentCourseIndex);
        }
    }

    private void showCourse() {
        if (currentCourseIndex >= courses.size()) return;
        Course course = courses.get(currentCourseIndex);
        int lang = courseRegistry.getLanguage();
        if (tvDescription != null) {
            tvDescription.setText(course.description(lang));
            tvDescription.setTextColor(theme.textDim);
            tvDescription.setBackgroundColor(theme.consoleBg);
        }

        CourseOutlineAdapter adapter = new CourseOutlineAdapter(
                course.chapters, theme, lang,
                lesson -> openLesson(course.id, lesson.id));
        rvChapters.setAdapter(adapter);
    }

    private void openLesson(String courseId, String lessonId) {
        Intent intent = new Intent(this, LessonActivity.class);
        intent.putExtra(LessonActivity.EXTRA_COURSE, courseId);
        intent.putExtra(LessonActivity.EXTRA_LESSON, lessonId);
        startActivity(intent);
    }
}
