package com.ccs.wiredroid.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ccs.wiredroid.util.AppPreferences;
import com.ccs.wiredroid.util.AppTheme;

/** Stable, font-independent language rows with accurately drawn country flags. */
final class LanguageAdapter extends BaseAdapter {
    static final class Item {
        final String tag, label, country;
        Item(String tag, String label, String country) {
            this.tag = tag;
            this.label = label;
            this.country = country;
        }
    }

    private final Context context;
    private final Item[] items;
    private final float density;
    private final AppTheme theme;

    LanguageAdapter(Context context, Item[] items) {
        this(context, items, null);
    }

    LanguageAdapter(Context context, Item[] items, AppTheme theme) {
        this.context = context;
        this.items = items;
        this.density = context.getResources().getDisplayMetrics().density;
        if (theme != null) {
            this.theme = theme;
        } else {
            AppPreferences prefs = new AppPreferences(context);
            this.theme = AppTheme.byId(prefs.getThemeId(), prefs);
        }
    }

    @Override public int getCount() { return items.length; }
    @Override public Item getItem(int position) { return items[position]; }
    @Override public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        return createOrUpdateRow(position, convertView, false);
    }

    @Override
    public View getDropDownView(int position, View convertView, ViewGroup parent) {
        return createOrUpdateRow(position, convertView, true);
    }

    private View createOrUpdateRow(int position, View convertView, boolean isDropdown) {
        LinearLayout row;
        ImageView flag;
        TextView text;

        if (convertView instanceof LinearLayout) {
            row = (LinearLayout) convertView;
            flag = (ImageView) row.findViewWithTag("flag");
            text = (TextView) row.findViewWithTag("text");
        } else {
            row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int padV = (int) ((isDropdown ? 10 : 6) * density);
            int padH = (int) (8 * density);
            row.setPadding(padH, padV, padH, padV);

            flag = new ImageView(context);
            flag.setTag("flag");
            int flagW = (int) (30 * density);
            int flagH = (int) (20 * density);
            LinearLayout.LayoutParams flagLp = new LinearLayout.LayoutParams(flagW, flagH);
            row.addView(flag, flagLp);

            text = new TextView(context);
            text.setTag("text");
            text.setTextSize(15);
            text.setPadding((int) (12 * density), 0, 0, 0);
            LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            row.addView(text, textLp);
        }

        Item item = items[position];
        int textColor = theme != null ? theme.text : 0xFF202020;
        flag.setImageDrawable(new FlagDrawable(item.country, textColor));
        text.setText(item.label);
        text.setTextColor(textColor);
        if (isDropdown && theme != null) {
            row.setBackgroundColor(theme.toolbar);
        }
        return row;
    }

    /**
     * A country's flag, drawn rather than shipped.
     *
     * <p>Public because the language picker is a popup menu now, not a spinner
     * backed by this adapter — the menu still wants the same flags, and they
     * exist only as drawing code.</p>
     *
     * @param inkColor the row's text colour, used by the entries that are a
     *                 glyph rather than a flag
     */
    static Drawable flag(String country, int inkColor) {
        return new FlagDrawable(country, inkColor);
    }

    private static final class FlagDrawable extends Drawable {
        private final String country;
        private final int inkColor;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clipPath = new Path();
        private final RectF rectF = new RectF();

        FlagDrawable(String country, int inkColor) {
            this.country = country != null ? country.toLowerCase(java.util.Locale.ROOT) : "";
            this.inkColor = inkColor;
        }

        /**
         * "Auto-detect" is an icon, not a flag.
         *
         * <p>It was drawn like one — a filled dark tile with a border — which on
         * a dark theme reads as a black box sitting in the row. Drawn as a plain
         * glyph on whatever is behind it instead.</p>
         */
        private boolean isGlyph() {
            return "auto".equals(country);
        }

        @Override
        public void draw(Canvas c) {
            float w = getBounds().width();
            float h = getBounds().height();
            if (w <= 0 || h <= 0) {
                w = 60;
                h = 40;
            }

            p.setStyle(Paint.Style.FILL);

            if (isGlyph()) {
                drawFlagContent(c, w, h);
                return;
            }

            // Clip to rounded rectangle
            clipPath.reset();
            rectF.set(0, 0, w, h);
            float radius = 5f;
            clipPath.addRoundRect(rectF, radius, radius, Path.Direction.CW);
            c.save();
            c.clipPath(clipPath);

            drawFlagContent(c, w, h);

            c.restore();

            // Subtle border outline so light-edged flags look crisp against any background
            p.setStyle(Paint.Style.STROKE);
            p.setColor(0x33888888);
            p.setStrokeWidth(1.5f);
            rectF.set(0.75f, 0.75f, w - 0.75f, h - 0.75f);
            c.drawRoundRect(rectF, radius, radius, p);
        }

        private void drawFlagContent(Canvas c, float w, float h) {
            switch (country) {
                case "auto": {
                    // A globe outline in the row's own ink, over the row's own
                    // background: no tile, no border, nothing to look like a
                    // black rectangle on a dark theme.
                    float cx = w / 2f, cy = h / 2f, r = Math.min(w, h) * 0.38f;
                    p.setStyle(Paint.Style.STROKE);
                    p.setColor(inkColor);
                    p.setStrokeWidth(1.5f);
                    c.drawCircle(cx, cy, r, p);
                    c.drawLine(cx - r, cy, cx + r, cy, p);
                    c.drawLine(cx, cy - r, cx, cy + r, p);
                    RectF oval = new RectF(cx - r * 0.5f, cy - r, cx + r * 0.5f, cy + r);
                    c.drawOval(oval, p);
                    p.setStyle(Paint.Style.FILL);
                    break;
                }
                case "ua": {
                    // Ukraine: Azure Blue (top) and Golden Yellow (bottom)
                    p.setColor(0xFF0057B7);
                    c.drawRect(0, 0, w, h / 2f, p);
                    p.setColor(0xFFFFD700);
                    c.drawRect(0, h / 2f, w, h, p);
                    break;
                }
                case "gb":
                case "en": {
                    // United Kingdom: Union Jack
                    p.setColor(0xFF012169); // Navy Blue background
                    c.drawRect(0, 0, w, h, p);

                    // White diagonal saltire
                    p.setColor(0xFFFFFFFF);
                    p.setStrokeWidth(h * 0.30f);
                    c.drawLine(0, 0, w, h, p);
                    c.drawLine(0, h, w, 0, p);

                    // Red diagonal saltire
                    p.setColor(0xFFC8102E);
                    p.setStrokeWidth(h * 0.14f);
                    c.drawLine(0, 0, w, h, p);
                    c.drawLine(0, h, w, 0, p);

                    // White St George cross
                    p.setColor(0xFFFFFFFF);
                    p.setStyle(Paint.Style.FILL);
                    c.drawRect(w * 0.36f, 0, w * 0.64f, h, p);
                    c.drawRect(0, h * 0.28f, w, h * 0.72f, p);

                    // Red St George cross
                    p.setColor(0xFFC8102E);
                    c.drawRect(w * 0.42f, 0, w * 0.58f, h, p);
                    c.drawRect(0, h * 0.38f, w, h * 0.62f, p);
                    break;
                }
                case "de": {
                    // Germany: Black, Red, Gold horizontal tricolor
                    p.setColor(0xFF000000);
                    c.drawRect(0, 0, w, h / 3f, p);
                    p.setColor(0xFFDD0000);
                    c.drawRect(0, h / 3f, w, (h * 2f) / 3f, p);
                    p.setColor(0xFFFFCE00);
                    c.drawRect(0, (h * 2f) / 3f, w, h, p);
                    break;
                }
                case "fr": {
                    // France: Blue, White, Red vertical tricolor
                    p.setColor(0xFF0055A4);
                    c.drawRect(0, 0, w / 3f, h, p);
                    p.setColor(0xFFFFFFFF);
                    c.drawRect(w / 3f, 0, (w * 2f) / 3f, h, p);
                    p.setColor(0xFFEF4135);
                    c.drawRect((w * 2f) / 3f, 0, w, h, p);
                    break;
                }
                case "it": {
                    // Italy: Green, White, Red vertical tricolor
                    p.setColor(0xFF009246);
                    c.drawRect(0, 0, w / 3f, h, p);
                    p.setColor(0xFFFFFFFF);
                    c.drawRect(w / 3f, 0, (w * 2f) / 3f, h, p);
                    p.setColor(0xFFCE2B37);
                    c.drawRect((w * 2f) / 3f, 0, w, h, p);
                    break;
                }
                case "es": {
                    // Spain: Red (1/4), Yellow (2/4), Red (1/4) horizontal
                    p.setColor(0xFFAA151B);
                    c.drawRect(0, 0, w, h * 0.25f, p);
                    p.setColor(0xFFF1BF00);
                    c.drawRect(0, h * 0.25f, w, h * 0.75f, p);
                    p.setColor(0xFFAA151B);
                    c.drawRect(0, h * 0.75f, w, h, p);

                    // Simplified coat of arms badge
                    p.setColor(0xFFAA151B);
                    c.drawRoundRect(w * 0.22f, h * 0.38f, w * 0.34f, h * 0.62f, 2, 2, p);
                    break;
                }
                case "pl": {
                    // Poland: White (top), Crimson Red (bottom) horizontal
                    p.setColor(0xFFFFFFFF);
                    c.drawRect(0, 0, w, h / 2f, p);
                    p.setColor(0xFFDC143C);
                    c.drawRect(0, h / 2f, w, h, p);
                    break;
                }
                case "ro": {
                    // Romania: Cobalt Blue, Chrome Yellow, Vermilion Red vertical tricolor
                    p.setColor(0xFF002B7F);
                    c.drawRect(0, 0, w / 3f, h, p);
                    p.setColor(0xFFFCD116);
                    c.drawRect(w / 3f, 0, (w * 2f) / 3f, h, p);
                    p.setColor(0xFFCE1126);
                    c.drawRect((w * 2f) / 3f, 0, w, h, p);
                    break;
                }
                case "az": {
                    // Azerbaijan: Cyan Blue, Red, Green horizontal tricolor with crescent & star
                    p.setColor(0xFF00B5E2);
                    c.drawRect(0, 0, w, h / 3f, p);
                    p.setColor(0xFFEF3340);
                    c.drawRect(0, h / 3f, w, (h * 2f) / 3f, p);
                    p.setColor(0xFF509E2F);
                    c.drawRect(0, (h * 2f) / 3f, w, h, p);

                    // White crescent and star in red stripe
                    float cx = w / 2f, cy = h / 2f;
                    p.setColor(0xFFFFFFFF);
                    c.drawCircle(cx - w * 0.03f, cy, h * 0.11f, p);
                    p.setColor(0xFFEF3340);
                    c.drawCircle(cx - w * 0.015f, cy, h * 0.09f, p);
                    p.setColor(0xFFFFFFFF);
                    c.drawCircle(cx + w * 0.07f, cy, h * 0.04f, p);
                    break;
                }
                case "in": {
                    // India: Saffron, White, Green horizontal tricolor with Ashoka Chakra
                    p.setColor(0xFFFF9933);
                    c.drawRect(0, 0, w, h / 3f, p);
                    p.setColor(0xFFFFFFFF);
                    c.drawRect(0, h / 3f, w, (h * 2f) / 3f, p);
                    p.setColor(0xFF138808);
                    c.drawRect(0, (h * 2f) / 3f, w, h, p);

                    // Ashoka Chakra (navy blue wheel)
                    float cx = w / 2f, cy = h / 2f;
                    p.setStyle(Paint.Style.STROKE);
                    p.setColor(0xFF000080);
                    p.setStrokeWidth(1.2f);
                    c.drawCircle(cx, cy, h * 0.11f, p);
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(cx, cy, h * 0.03f, p);
                    break;
                }
                case "ng": {
                    // Nigeria: Green, White, Green vertical tricolor
                    p.setColor(0xFF008751);
                    c.drawRect(0, 0, w / 3f, h, p);
                    p.setColor(0xFFFFFFFF);
                    c.drawRect(w / 3f, 0, (w * 2f) / 3f, h, p);
                    p.setColor(0xFF008751);
                    c.drawRect((w * 2f) / 3f, 0, w, h, p);
                    break;
                }
                case "ph": {
                    // Philippines: Blue (top), Red (bottom) with white equilateral triangle on left
                    p.setColor(0xFF0038A8);
                    c.drawRect(0, 0, w, h / 2f, p);
                    p.setColor(0xFFCE1126);
                    c.drawRect(0, h / 2f, w, h, p);

                    p.setColor(0xFFFFFFFF);
                    Path chevron = new Path();
                    chevron.moveTo(0, 0);
                    chevron.lineTo(w * 0.45f, h / 2f);
                    chevron.lineTo(0, h);
                    chevron.close();
                    c.drawPath(chevron, p);

                    // Yellow golden sun in triangle
                    p.setColor(0xFFFCD116);
                    c.drawCircle(w * 0.16f, h / 2f, h * 0.12f, p);
                    break;
                }
                case "br": {
                    // Brazil: Green field, Yellow rhombus, Blue circle
                    p.setColor(0xFF009C3B);
                    c.drawRect(0, 0, w, h, p);

                    p.setColor(0xFFFFDF00);
                    Path rhombus = new Path();
                    rhombus.moveTo(w / 2f, 2f);
                    rhombus.lineTo(w - 3f, h / 2f);
                    rhombus.lineTo(w / 2f, h - 2f);
                    rhombus.lineTo(3f, h / 2f);
                    rhombus.close();
                    c.drawPath(rhombus, p);

                    p.setColor(0xFF002776);
                    c.drawCircle(w / 2f, h / 2f, h * 0.22f, p);

                    p.setColor(0xFFFFFFFF);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(1.2f);
                    RectF globeRect = new RectF(w / 2f - h * 0.22f, h / 2f - h * 0.22f,
                            w / 2f + h * 0.22f, h / 2f + h * 0.22f);
                    c.drawArc(globeRect, 190, 160, false, p);
                    p.setStyle(Paint.Style.FILL);
                    break;
                }
                case "jp": {
                    // Japan: White field with Crimson Red disc
                    p.setColor(0xFFFFFFFF);
                    c.drawRect(0, 0, w, h, p);
                    p.setColor(0xFFBC002D);
                    c.drawCircle(w / 2f, h / 2f, h * 0.28f, p);
                    break;
                }
                case "cz": {
                    // Czech Republic: White (top), Red (bottom) with Blue triangle
                    p.setColor(0xFFFFFFFF);
                    c.drawRect(0, 0, w, h / 2f, p);
                    p.setColor(0xFFD7141A);
                    c.drawRect(0, h / 2f, w, h, p);

                    p.setColor(0xFF11457E);
                    Path tri = new Path();
                    tri.moveTo(0, 0);
                    tri.lineTo(w * 0.5f, h / 2f);
                    tri.lineTo(0, h);
                    tri.close();
                    c.drawPath(tri, p);
                    break;
                }
                case "sk": {
                    // Slovakia: White, Blue, Red horizontal tricolor with shield
                    p.setColor(0xFFFFFFFF);
                    c.drawRect(0, 0, w, h / 3f, p);
                    p.setColor(0xFF0B4EA2);
                    c.drawRect(0, h / 3f, w, (h * 2f) / 3f, p);
                    p.setColor(0xFFEE1C25);
                    c.drawRect(0, (h * 2f) / 3f, w, h, p);

                    // Shield
                    p.setColor(0xFFEE1C25);
                    RectF shield = new RectF(w * 0.14f, h * 0.18f, w * 0.40f, h * 0.82f);
                    c.drawRoundRect(shield, 4f, 4f, p);
                    p.setColor(0xFFFFFFFF);
                    float scx = (shield.left + shield.right) / 2f;
                    c.drawLine(scx, shield.top + 4f, scx, shield.bottom - 4f, p);
                    c.drawLine(shield.left + 4f, shield.top + 10f, shield.right - 4f, shield.top + 10f, p);
                    break;
                }
                case "tr": {
                    // Turkey: Red field with White crescent & star
                    p.setColor(0xFFE30A17);
                    c.drawRect(0, 0, w, h, p);

                    float cx = w * 0.40f, cy = h / 2f;
                    p.setColor(0xFFFFFFFF);
                    c.drawCircle(cx, cy, h * 0.28f, p);
                    p.setColor(0xFFE30A17);
                    c.drawCircle(cx + w * 0.07f, cy, h * 0.22f, p);

                    // White star
                    p.setColor(0xFFFFFFFF);
                    c.drawCircle(w * 0.65f, cy, h * 0.08f, p);
                    break;
                }
                case "kr": {
                    // South Korea: White field with Taegeuk (Red/Blue yin-yang)
                    p.setColor(0xFFFFFFFF);
                    c.drawRect(0, 0, w, h, p);

                    float cx = w / 2f, cy = h / 2f, r = h * 0.25f;
                    p.setColor(0xFFCD2E3A);
                    RectF tRect = new RectF(cx - r, cy - r, cx + r, cy + r);
                    c.drawArc(tRect, 180, 180, true, p);
                    p.setColor(0xFF0047A0);
                    c.drawArc(tRect, 0, 180, true, p);

                    p.setColor(0xFFCD2E3A);
                    c.drawCircle(cx - r / 2f, cy, r / 2f, p);
                    p.setColor(0xFF0047A0);
                    c.drawCircle(cx + r / 2f, cy, r / 2f, p);
                    break;
                }
                case "cn": {
                    // China: Red field with yellow stars
                    p.setColor(0xFFDE2910);
                    c.drawRect(0, 0, w, h, p);

                    p.setColor(0xFFFFDE00);
                    c.drawCircle(w * 0.22f, h * 0.32f, h * 0.16f, p);
                    c.drawCircle(w * 0.40f, h * 0.16f, h * 0.05f, p);
                    c.drawCircle(w * 0.48f, h * 0.28f, h * 0.05f, p);
                    c.drawCircle(w * 0.48f, h * 0.44f, h * 0.05f, p);
                    c.drawCircle(w * 0.40f, h * 0.56f, h * 0.05f, p);
                    break;
                }
                case "vi": {
                    // Vietnam: Red field with large yellow star in center
                    p.setColor(0xFFDA251D);
                    c.drawRect(0, 0, w, h, p);

                    p.setColor(0xFFFFFF00);
                    float cx = w / 2f, cy = h / 2f;
                    float r = h * 0.30f;
                    Path star = new Path();
                    for (int i = 0; i < 5; i++) {
                        double aOuter = -Math.PI / 2 + i * 2 * Math.PI / 5;
                        double aInner = aOuter + Math.PI / 5;
                        float ox = cx + r * (float) Math.cos(aOuter);
                        float oy = cy + r * (float) Math.sin(aOuter);
                        float ix = cx + (r * 0.38f) * (float) Math.cos(aInner);
                        float iy = cy + (r * 0.38f) * (float) Math.sin(aInner);
                        if (i == 0) star.moveTo(ox, oy);
                        else star.lineTo(ox, oy);
                        star.lineTo(ix, iy);
                    }
                    star.close();
                    c.drawPath(star, p);
                    break;
                }
                case "id": {
                    // Indonesia: Red (top), White (bottom)
                    p.setColor(0xFFFF0000);
                    c.drawRect(0, 0, w, h / 2f, p);
                    p.setColor(0xFFFFFFFF);
                    c.drawRect(0, h / 2f, w, h, p);
                    break;
                }
                case "nl": {
                    // Netherlands: Red, White, Cobalt Blue horizontal tricolor
                    p.setColor(0xFFAE1C28);
                    c.drawRect(0, 0, w, h / 3f, p);
                    p.setColor(0xFFFFFFFF);
                    c.drawRect(0, h / 3f, w, (h * 2f) / 3f, p);
                    p.setColor(0xFF21468B);
                    c.drawRect(0, (h * 2f) / 3f, w, h, p);
                    break;
                }
                case "ge": {
                    // Georgia: White field with bold Red cross + 4 small red crosses
                    p.setColor(0xFFFFFFFF);
                    c.drawRect(0, 0, w, h, p);

                    p.setColor(0xFFFF0000);
                    // Central cross
                    c.drawRect(w * 0.40f, 0, w * 0.60f, h, p);
                    c.drawRect(0, h * 0.38f, w, h * 0.62f, p);

                    // 4 corner crosses
                    float q1x = w * 0.20f, q1y = h * 0.19f;
                    float q2x = w * 0.80f, q2y = h * 0.19f;
                    float q3x = w * 0.20f, q3y = h * 0.81f;
                    float q4x = w * 0.80f, q4y = h * 0.81f;
                    float cr = 3.5f;
                    for (float[] pt : new float[][]{{q1x, q1y}, {q2x, q2y}, {q3x, q3y}, {q4x, q4y}}) {
                        c.drawRect(pt[0] - cr, pt[1] - 1f, pt[0] + cr, pt[1] + 1f, p);
                        c.drawRect(pt[0] - 1f, pt[1] - cr, pt[0] + 1f, pt[1] + cr, p);
                    }
                    break;
                }
                case "sa": {
                    // Saudi Arabia / Arabic: Forest Green field with white sword
                    p.setColor(0xFF006C35);
                    c.drawRect(0, 0, w, h, p);

                    p.setColor(0xFFFFFFFF);
                    // White sword
                    c.drawRect(w * 0.22f, h * 0.62f, w * 0.78f, h * 0.68f, p);
                    c.drawCircle(w * 0.22f, h * 0.65f, 2.5f, p);
                    // Inscription block
                    c.drawRect(w * 0.25f, h * 0.32f, w * 0.75f, h * 0.48f, p);
                    break;
                }
                case "dk": {
                    // Denmark: white Nordic cross on red
                    nordicCross(c, w, h, 0xFFC8102E, 0xFFFFFFFF, 0);
                    break;
                }
                case "se": {
                    // Sweden: yellow Nordic cross on blue
                    nordicCross(c, w, h, 0xFF006AA7, 0xFFFECC00, 0);
                    break;
                }
                case "fi": {
                    // Finland: blue Nordic cross on white
                    nordicCross(c, w, h, 0xFFFFFFFF, 0xFF003580, 0);
                    break;
                }
                case "no": {
                    // Norway: blue cross fimbriated white, on red
                    nordicCross(c, w, h, 0xFFBA0C2F, 0xFFFFFFFF, 0xFF00205B);
                    break;
                }
                case "is": {
                    // Iceland: red cross fimbriated white, on blue
                    nordicCross(c, w, h, 0xFF02529C, 0xFFFFFFFF, 0xFFDC1E35);
                    break;
                }
                case "ee": {
                    // Estonia: blue, black, white horizontal tricolor
                    p.setColor(0xFF0072CE);
                    c.drawRect(0, 0, w, h / 3f, p);
                    p.setColor(0xFF000000);
                    c.drawRect(0, h / 3f, w, (h * 2f) / 3f, p);
                    p.setColor(0xFFFFFFFF);
                    c.drawRect(0, (h * 2f) / 3f, w, h, p);
                    break;
                }
                case "lv": {
                    // Latvia: carmine with a narrow white band across the middle
                    p.setColor(0xFF9E3039);
                    c.drawRect(0, 0, w, h, p);
                    p.setColor(0xFFFFFFFF);
                    c.drawRect(0, h * 0.4f, w, h * 0.6f, p);
                    break;
                }
                case "ie": {
                    // Ireland: green, white, orange vertical tricolor
                    p.setColor(0xFF169B62);
                    c.drawRect(0, 0, w / 3f, h, p);
                    p.setColor(0xFFFFFFFF);
                    c.drawRect(w / 3f, 0, (w * 2f) / 3f, h, p);
                    p.setColor(0xFFFF883E);
                    c.drawRect((w * 2f) / 3f, 0, w, h, p);
                    break;
                }
                case "am": {
                    // Armenia: red, blue, apricot horizontal tricolor
                    p.setColor(0xFFD90012);
                    c.drawRect(0, 0, w, h / 3f, p);
                    p.setColor(0xFF0033A0);
                    c.drawRect(0, h / 3f, w, (h * 2f) / 3f, p);
                    p.setColor(0xFFF2A800);
                    c.drawRect(0, (h * 2f) / 3f, w, h, p);
                    break;
                }
                case "sct": {
                    // Scotland: the Saltire, a white diagonal cross on blue
                    p.setColor(0xFF005EB8);
                    c.drawRect(0, 0, w, h, p);
                    p.setColor(0xFFFFFFFF);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(Math.max(2f, h * 0.2f));
                    c.drawLine(0, 0, w, h, p);
                    c.drawLine(w, 0, 0, h, p);
                    p.setStyle(Paint.Style.FILL);
                    break;
                }
                case "gr": {
                    // Greece: nine stripes with a cross in the canton
                    p.setColor(0xFFFFFFFF);
                    c.drawRect(0, 0, w, h, p);
                    p.setColor(0xFF0D5EAF);
                    float stripe = h / 9f;
                    for (int i = 0; i < 9; i += 2) {
                        c.drawRect(0, i * stripe, w, (i + 1) * stripe, p);
                    }
                    // Canton: five stripes tall and as wide, so it is square.
                    float canton = stripe * 5f;
                    c.drawRect(0, 0, canton, canton, p);
                    p.setColor(0xFFFFFFFF);
                    c.drawRect(canton * 0.4f, 0, canton * 0.6f, canton, p);
                    c.drawRect(0, canton * 0.4f, canton, canton * 0.6f, p);
                    break;
                }
                case "my": {
                    // Malaysia: red and white stripes, blue canton, crescent and star
                    p.setColor(0xFFFFFFFF);
                    c.drawRect(0, 0, w, h, p);
                    p.setColor(0xFFCC0001);
                    float band = h / 14f;
                    for (int i = 0; i < 14; i += 2) {
                        c.drawRect(0, i * band, w, (i + 1) * band, p);
                    }
                    p.setColor(0xFF010066);
                    c.drawRect(0, 0, w * 0.5f, band * 8f, p);

                    // The crescent is one disc with a second cut out of it, and
                    // the cut is painted in the canton's own blue.
                    float cy = band * 4f;
                    float r = band * 2.6f;
                    p.setColor(0xFFFFCC00);
                    c.drawCircle(w * 0.2f, cy, r, p);
                    p.setColor(0xFF010066);
                    c.drawCircle(w * 0.26f, cy, r * 0.85f, p);
                    p.setColor(0xFFFFCC00);
                    c.drawCircle(w * 0.36f, cy, r * 0.42f, p);
                    break;
                }
                default: {
                    // Generic fallback
                    p.setColor(0xFF4A86C8);
                    c.drawRect(0, 0, w, h, p);
                    p.setColor(0xFFFFFFFF);
                    c.drawCircle(w / 2f, h / 2f, Math.min(w, h) * 0.25f, p);
                    break;
                }
            }
        }

        /**
         * The cross shared by every Nordic flag, off-centre toward the hoist.
         *
         * @param inner the colour of a cross drawn inside the first one, for the
         *              two flags whose cross is edged in a second colour; 0 for
         *              the plain ones
         */
        private void nordicCross(Canvas c, float w, float h, int field, int cross, int inner) {
            p.setColor(field);
            c.drawRect(0, 0, w, h, p);

            float armY = h * 0.11f;
            float armX = w * 0.11f;
            float centreX = w * 0.36f;
            float centreY = h * 0.5f;

            p.setColor(cross);
            c.drawRect(centreX - armX, 0, centreX + armX, h, p);
            c.drawRect(0, centreY - armY, w, centreY + armY, p);

            if (inner != 0) {
                p.setColor(inner);
                c.drawRect(centreX - armX * 0.45f, 0, centreX + armX * 0.45f, h, p);
                c.drawRect(0, centreY - armY * 0.45f, w, centreY + armY * 0.45f, p);
            }
        }

        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { p.setColorFilter(f); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
}
