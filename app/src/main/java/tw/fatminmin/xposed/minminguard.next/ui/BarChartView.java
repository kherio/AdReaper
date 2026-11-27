package tw.fatminmin.xposed.minminguard.next.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import com.google.android.material.color.MaterialColors;

/** Minimal bar chart of one value per day, drawn with the current theme colors. */
public class BarChartView extends View
{
    private int[] values = new int[0];
    private String[] labels = new String[0];
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    public BarChartView(Context c, AttributeSet a)
    {
        super(c, a);
        bar.setColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary));
        text.setColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant));
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11, getResources().getDisplayMetrics()));
    }

    public void setData(int[] values, String[] labels)
    {
        this.values = values;
        this.labels = labels;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h)
    {
        int height = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 140, getResources().getDisplayMetrics());
        setMeasuredDimension(resolveSize(getSuggestedMinimumWidth(), w), height);
    }

    @Override
    protected void onDraw(Canvas canvas)
    {
        int n = values.length;
        if (n == 0) return;
        int max = 1;
        for (int v : values) max = Math.max(max, v);

        float slotW = getWidth() / (float) n;
        float base = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11, getResources().getDisplayMetrics());
        float widest = 1;
        text.setTextSize(base);
        for (String l : labels) if (l != null) widest = Math.max(widest, text.measureText(l));
        if (widest > slotW * 0.95f) text.setTextSize(Math.max(base * slotW * 0.95f / widest, base * 0.6f));

        float labelH = text.getTextSize() + 6;
        float top = text.getTextSize() + 6; // room for the value above the tallest bar
        float chartH = getHeight() - labelH - top;
        float slot = getWidth() / (float) n;
        float barW = slot * 0.6f;

        for (int i = 0; i < n; i++)
        {
            float h = chartH * values[i] / max;
            float left = i * slot + (slot - barW) / 2;
            float bottom = top + chartH;
            rect.set(left, bottom - Math.max(h, values[i] > 0 ? 3 : 1), left + barW, bottom);
            canvas.drawRoundRect(rect, 4, 4, bar);
            if (values[i] > 0)
            {
                canvas.drawText(String.valueOf(values[i]), left + barW / 2, rect.top - 4, text);
            }
            canvas.drawText(labels[i], left + barW / 2, getHeight() - 4, text);
        }
    }
}
