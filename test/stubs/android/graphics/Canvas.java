package android.graphics;
import java.awt.*;
import java.awt.geom.*;
public class Canvas {
    final Graphics2D g;
    public Canvas(Graphics2D g) {
        this.g = g;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    }
    private void paint(Paint p) { g.setColor(new java.awt.Color(p.color, true)); g.setStroke(new BasicStroke(p.stroke, p.cap == Paint.Cap.ROUND ? BasicStroke.CAP_ROUND : p.cap == Paint.Cap.SQUARE ? BasicStroke.CAP_SQUARE : BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)); }
    private final java.util.ArrayDeque<Object[]> saved = new java.util.ArrayDeque<Object[]>();
    public int save() { saved.push(new Object[]{g.getTransform(), g.getClip()}); return saved.size(); }
    public void restore() { Object[] o = saved.pop(); g.setTransform((AffineTransform) o[0]); g.setClip((Shape) o[1]); }
    public void translate(float x, float y) { g.translate(x, y); }
    public boolean clipRect(float l, float t, float r, float b) { g.clip(new Rectangle2D.Float(l, t, r - l, b - t)); return true; }
    public void drawLine(float x1, float y1, float x2, float y2, Paint p) { paint(p); g.draw(new Line2D.Float(x1, y1, x2, y2)); }
    private void shape(Shape s, Paint p) { paint(p); if (p.style == Paint.Style.STROKE) g.draw(s); else g.fill(s); }
    public void drawColor(int c) { g.setColor(new java.awt.Color(c, true)); g.fillRect(0, 0, 100000, 100000); }
    public void drawRect(float l, float t, float r, float b, Paint p) { shape(new Rectangle2D.Float(l, t, r - l, b - t), p); }
    public void drawRoundRect(RectF r, float rx, float ry, Paint p) { shape(new RoundRectangle2D.Float(r.left, r.top, r.width(), r.height(), rx * 2, ry * 2), p); }
    public void drawCircle(float cx, float cy, float rad, Paint p) { shape(new Ellipse2D.Float(cx - rad, cy - rad, rad * 2, rad * 2), p); }
    public void drawPath(Path path, Paint p) { shape(path.p, p); }
    public void drawBitmap(Bitmap b, Rect src, RectF dst, Paint p) {
        g.drawImage(b.img, Math.round(dst.left), Math.round(dst.top), Math.round(dst.right), Math.round(dst.bottom),
                src.left, src.top, src.right, src.bottom, null);
    }
    public void drawText(String s, float x, float y, Paint p) {
        if (s == null) throw new NullPointerException("drawText(null)");
        paint(p);
        g.setFont(p.font());
        float w = p.measureText(s);
        if (p.align == Paint.Align.CENTER) x -= w / 2; else if (p.align == Paint.Align.RIGHT) x -= w;
        g.drawString(s, x, y);
    }
}
