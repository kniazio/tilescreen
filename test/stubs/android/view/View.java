package android.view;
public class View {
    private final android.content.Context context;
    private int w, h;
    public int invalidations;
    public View(android.content.Context c) { context = c; }
    public android.content.Context getContext() { return context; }
    public int getWidth() { return w; }
    public int getHeight() { return h; }
    public void layout(int w, int h) { int ow = this.w, oh = this.h; this.w = w; this.h = h; onSizeChanged(w, h, ow, oh); }
    public void invalidate() { invalidations++; }
    public void postInvalidate() { invalidations++; }
    protected void onAttachedToWindow() {}
    protected void onDetachedFromWindow() {}
    public void setKeepScreenOn(boolean b) {}
    public static final int VISIBLE = 0, INVISIBLE = 4, GONE = 8;
    private int visibility = VISIBLE;
    public int getVisibility() { return visibility; }
    public void setVisibility(int v) { visibility = v; }
    public boolean performHapticFeedback(int f) { return true; }
    protected void onSizeChanged(int w, int h, int ow, int oh) {}
    protected void onDraw(android.graphics.Canvas c) {}
    public void draw(android.graphics.Canvas c) { onDraw(c); }
    public boolean onTouchEvent(MotionEvent e) { return false; }
}
