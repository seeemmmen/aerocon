package pl.aerocon.ui;

import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import pl.aerocon.model.Flight;

import java.util.List;
import java.util.function.Consumer;

/** A vector radar surface. Flight positions use normalized sector coordinates. */
public final class RadarView extends Region {
    private static final Color BACKGROUND = Color.web("#091827");
    private static final Color GRID = Color.web("#112b39");
    private static final Color RING = Color.web("#12434d");
    private static final Color MUTED = Color.web("#71889b");
    private static final Color CYAN = Color.web("#00dbc1");
    private static final Color RED = Color.web("#ed2863");
    private static final double BASE_RANGE = 15.0;

    private final Canvas canvas = new Canvas();
    private final List<Flight> flights;
    private final Consumer<Flight> onSelect;
    private Flight selected;
    private Consumer<double[]> picker;
    private int range = 15;
    private boolean trajectoryVisible;
    private double sweep = 32;

    public RadarView(List<Flight> flights, Consumer<Flight> onSelect) {
        this.flights = flights;
        this.onSelect = onSelect;
        getChildren().add(canvas);
        setMinSize(200, 260);
        setPrefSize(720, 640);
        canvas.setOnMouseClicked(event -> {
            if (picker != null) {
                double x = clamp(.5 + (event.getX() - canvas.getWidth() / 2) / worldWidth(), .05, .95);
                double y = clamp(.5 + (event.getY() - canvas.getHeight() / 2) / worldHeight(), .05, .95);
                Consumer<double[]> callback = picker;
                picker = null;
                canvas.setCursor(Cursor.DEFAULT);
                callback.accept(new double[]{x, y});
            } else {
                Flight hit = hitFlight(event.getX(), event.getY());
                if (hit != null) {
                    selected = hit;
                    redraw();
                    onSelect.accept(hit);
                }
            }
        });
        canvas.setOnMouseMoved(event -> canvas.setCursor(picker != null ? Cursor.CROSSHAIR
                : hitFlight(event.getX(), event.getY()) != null ? Cursor.HAND : Cursor.DEFAULT));
    }

    public void setSelected(Flight flight) {
        selected = flight;
        redraw();
    }

    public void setRange(int nm) {
        range = Math.max(5, Math.min(60, nm));
        redraw();
    }

    public void setTrajectoryVisible(boolean visible) {
        trajectoryVisible = visible;
        redraw();
    }

    public void setSweep(double degrees) {
        sweep = degrees % 360;
        redraw();
    }

    /** Installs a one-shot callback for selecting the initial position of a new flight. */
    public void setPicking(Consumer<double[]> picker) {
        this.picker = picker;
        canvas.setCursor(picker == null ? Cursor.DEFAULT : Cursor.CROSSHAIR);
        redraw();
    }

    @Override
    protected void layoutChildren() {
        canvas.setWidth(getWidth());
        canvas.setHeight(getHeight());
        redraw();
    }

    public void redraw() {
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        if (width < 1 || height < 1) return;
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setGlobalAlpha(1);
        g.setLineDashes();
        g.setFill(BACKGROUND);
        g.fillRect(0, 0, width, height);
        g.save();
        g.beginPath();
        g.rect(0, 0, width, height);
        g.clip();

        drawGrid(g, width, height);
        drawScope(g, width, height);
        drawConflict(g, width, height);
        for (Flight flight : flights) drawFlight(g, flight, width, height);
        drawInstruments(g, width, height);

        if (picker != null) {
            g.setFill(Color.web("#0e2b38", .96));
            double boxWidth = Math.min(360, width - 32);
            g.fillRoundRect((width - boxWidth) / 2, height - 68, boxWidth, 34, 4, 4);
            g.setFont(Font.font("Consolas", 10));
            g.setTextAlign(TextAlignment.CENTER);
            g.setFill(CYAN);
            g.fillText("CLICK ON RADAR TO PLACE NEW FLIGHT", width / 2, height - 47);
        }
        g.restore();
    }

    private void drawGrid(GraphicsContext g, double width, double height) {
        double step = 32;
        double cx = width / 2;
        double cy = height / 2;
        g.setLineWidth(.65);
        g.setStroke(GRID);
        for (double x = cx % step; x < width; x += step) g.strokeLine(x, 0, x, height);
        for (double y = cy % step; y < height; y += step) g.strokeLine(0, y, width, y);
        g.setStroke(Color.web("#153642"));
        g.setLineWidth(.8);
        g.strokeLine(cx, 0, cx, height);
        g.strokeLine(0, cy, width, cy);
    }

    private void drawScope(GraphicsContext g, double width, double height) {
        double cx = width / 2;
        double cy = height / 2;
        double radius = Math.min(width, height) * .46;

        g.setStroke(RING);
        g.setLineWidth(.8);
        for (int i = 1; i <= 4; i++) {
            double r = radius * i / 4;
            g.strokeOval(cx - r, cy - r, r * 2, r * 2);
        }
        g.setStroke(Color.web("#10404a", .65));
        for (int angle = 0; angle < 360; angle += 30) {
            double a = Math.toRadians(angle - 90);
            double r1 = radius - (angle % 90 == 0 ? 9 : 5);
            g.strokeLine(cx + Math.cos(a) * r1, cy + Math.sin(a) * r1,
                    cx + Math.cos(a) * radius, cy + Math.sin(a) * radius);
        }
        double a = Math.toRadians(sweep - 90);
        g.setFill(Color.web("#00dbc1", .018));
        g.fillArc(cx - radius, cy - radius, radius * 2, radius * 2,
                90 - sweep, 20, ArcType.ROUND);
        g.setStroke(Color.web("#00dbc1", .14));
        g.strokeLine(cx, cy, cx + Math.cos(a) * radius, cy + Math.sin(a) * radius);

        g.setFont(Font.font("Consolas", 10));
        g.setFill(Color.web("#00dbc1", .65));
        g.setTextAlign(TextAlignment.CENTER);
        g.fillText("N", cx, Math.max(13, cy - radius - 8));
        g.setFill(Color.web("#547683", .7));
        g.fillText("E", Math.min(width - 9, cx + radius + 12), cy + 3);
        g.fillText("W", Math.max(9, cx - radius - 12), cy + 3);
        g.fillText("S", cx, Math.min(height - 12, cy + radius + 14));
        g.setFill(CYAN);
        g.fillOval(cx - 2.5, cy - 2.5, 5, 5);
        g.setStroke(Color.web("#00dbc1", .55));
        g.strokeLine(cx - 7, cy, cx - 4, cy);
        g.strokeLine(cx + 4, cy, cx + 7, cy);
        g.strokeLine(cx, cy - 7, cx, cy - 4);
        g.strokeLine(cx, cy + 4, cx, cy + 7);
        g.setTextAlign(TextAlignment.LEFT);
    }

    private void drawConflict(GraphicsContext g, double width, double height) {
        List<Flight> conflicts = flights.stream().filter(f -> f.conflict).toList();
        if (conflicts.size() < 2) return;
        int selectedIndex = conflicts.indexOf(selected);
        int annotatedPair = selectedIndex >= 0 && selectedIndex / 2 * 2 + 1 < conflicts.size()
                ? selectedIndex / 2 * 2 : 0;
        double cx = 0, cy = 0;
        for (int i = 0; i + 1 < conflicts.size(); i += 2) {
            Flight a = conflicts.get(i);
            Flight b = conflicts.get(i + 1);
            double ax = screenX(a.x), ay = screenY(a.y);
            double bx = screenX(b.x), by = screenY(b.y);
            double[] cpa = intersection(a, b);
            double pairX = screenX(cpa[0]), pairY = screenY(cpa[1]);
            g.setStroke(Color.web("#ed2863", .72));
            g.setLineWidth(1.1);
            g.setLineDashes(5, 5);
            g.strokeLine(ax, ay, pairX, pairY);
            g.strokeLine(bx, by, pairX, pairY);
            g.setLineDashes();
            g.setFill(Color.web("#ed2863", .075));
            g.fillOval(pairX - 21, pairY - 21, 42, 42);
            g.setStroke(Color.web("#ed2863", .4));
            g.strokeOval(pairX - 21, pairY - 21, 42, 42);
            g.strokeLine(pairX - 6, pairY - 6, pairX + 6, pairY + 6);
            g.strokeLine(pairX - 6, pairY + 6, pairX + 6, pairY - 6);
            if (i == annotatedPair) {
                cx = pairX;
                cy = pairY;
            }
        }

        // A single callout keeps dense traffic readable; selecting a conflict changes its focus.
        double labelWidth = 118;
        double lx = clamp(cx + 28, 8, width - labelWidth - 8);
        double ly = clamp(cy + 1, 28, height - 58);
        g.setFill(Color.web("#39172e", .86));
        g.fillRoundRect(lx, ly, labelWidth, 42, 3, 3);
        g.setStroke(Color.web("#ed2863", .35));
        g.strokeRoundRect(lx, ly, labelWidth, 42, 3, 3);
        g.setFont(Font.font("Consolas", 10));
        g.setFill(RED);
        g.fillText("CPA  /  01:08", lx + 9, ly + 16);
        g.setFont(Font.font("Consolas", 9));
        g.setFill(Color.web("#d1a0af"));
        g.fillText("0.8 NM · 400 FT", lx + 9, ly + 31);
    }

    private void drawFlight(GraphicsContext g, Flight flight, double width, double height) {
        double x = screenX(flight.x), y = screenY(flight.y);
        if (x < -20 || x > width + 20 || y < -20 || y > height + 20) return;
        Color color = flight.conflict ? RED : CYAN;
        boolean isSelected = flight == selected;
        double heading = Math.toRadians(flight.heading - 90);
        double dx = Math.cos(heading), dy = Math.sin(heading);

        g.setStroke(flight.conflict ? Color.web("#ed2863", .32) : Color.web("#00dbc1", .22));
        g.setLineWidth(.8);
        g.setLineDashes(3, 5);
        g.strokeLine(x, y, x + dx * 65, y + dy * 65);
        g.setLineDashes();
        for (int i = 1; i <= 4; i++) {
            g.setFill(color.deriveColor(0, 1, 1, .23 - i * .035));
            g.fillRect(x - dx * i * 11 - 1, y - dy * i * 11 - 1, 2, 2);
        }

        if (isSelected) {
            g.setStroke(color.deriveColor(0, 1, 1, .65));
            g.setLineWidth(.9);
            drawBrackets(g, x, y, 13);
            g.setFill(color.deriveColor(0, 1, 1, .055));
            g.fillRect(x - 13, y - 13, 26, 26);
        }

        if (isSelected && trajectoryVisible) {
            g.setStroke(color.deriveColor(0, 1, 1, .65));
            g.setLineWidth(1);
            g.setLineDashes(7, 6);
            g.strokeLine(x - dx * 140, y - dy * 140, x + dx * 190, y + dy * 190);
            g.setLineDashes();
            for (int i = 1; i <= 3; i++) {
                double tx = x + dx * i * 58, ty = y + dy * i * 58;
                g.strokeOval(tx - 3, ty - 3, 6, 6);
            }
        }

        g.save();
        g.translate(x, y);
        g.rotate(flight.heading);
        g.setStroke(color);
        g.setFill(color.deriveColor(0, 1, 1, .12));
        g.setLineWidth(1.05);
        g.beginPath();
        g.moveTo(0, -8);
        g.lineTo(2, -2);
        g.lineTo(8, 2);
        g.lineTo(8, 4);
        g.lineTo(2, 2);
        g.lineTo(1.6, 6);
        g.lineTo(4, 8);
        g.lineTo(4, 9);
        g.lineTo(0, 7.5);
        g.lineTo(-4, 9);
        g.lineTo(-4, 8);
        g.lineTo(-1.6, 6);
        g.lineTo(-2, 2);
        g.lineTo(-8, 4);
        g.lineTo(-8, 2);
        g.lineTo(-2, -2);
        g.closePath();
        g.fill();
        g.stroke();
        g.restore();

        if (flights.size() > 18 && !isSelected && !flight.conflict) return;
        double lx = x + 16;
        double ly = y + 17;
        if (lx + 89 > width - 7) lx = x - 92;
        if (ly + 33 > height - 22) ly = y - 36;
        lx = Math.max(7, lx);
        ly = Math.max(20, ly);
        g.setFont(Font.font("Consolas", FontWeight.BOLD, 11));
        g.setFill(color);
        g.fillText(flight.callsign, lx, ly);
        g.setFont(Font.font("Consolas", 9));
        g.setFill(Color.web("#a6b9c8"));
        g.fillText(String.format("%,d FT", flight.altitude), lx, ly + 13);
        g.setFill(MUTED);
        g.fillText(flight.speed + " KT", lx, ly + 25);
    }

    private void drawInstruments(GraphicsContext g, double width, double height) {
        g.setFont(Font.font("Consolas", 9));
        g.setFill(MUTED);
        g.fillText("SSR / ADS-B", 15, 18);
        g.setFill(Color.web("#547383"));
        g.fillText("51°28′ N   000°27′ W", 15, 31);
        g.setTextAlign(TextAlignment.RIGHT);
        g.fillText(String.format("%02d NM", range), width - 14, 18);
        g.fillText("LAST SWEEP  0.8 S", width - 14, height - 14);
        g.setTextAlign(TextAlignment.LEFT);
        g.setStroke(MUTED.deriveColor(0, 1, 1, .7));
        g.setLineWidth(.8);
        double scaleWidth = Math.min(64, width / 7);
        double y = height - 25;
        g.strokeLine(15, y, 15 + scaleWidth, y);
        g.strokeLine(15, y - 3, 15, y + 3);
        g.strokeLine(15 + scaleWidth, y - 3, 15 + scaleWidth, y + 3);
        g.setFill(MUTED);
        g.setFont(Font.font("Consolas", 8));
        g.fillText("0", 14, height - 12);
        g.fillText(Math.max(1, range / 3) + " NM", 15 + scaleWidth - 21, height - 12);
    }

    private void drawBrackets(GraphicsContext g, double x, double y, double r) {
        double l = 5;
        for (int sx : new int[]{-1, 1}) {
            for (int sy : new int[]{-1, 1}) {
                g.strokeLine(x + sx * r, y + sy * r, x + sx * (r - l), y + sy * r);
                g.strokeLine(x + sx * r, y + sy * r, x + sx * r, y + sy * (r - l));
            }
        }
    }

    private Flight hitFlight(double x, double y) {
        Flight nearest = null;
        double nearestDistance = 20;
        for (Flight flight : flights) {
            double distance = Math.hypot(screenX(flight.x) - x, screenY(flight.y) - y);
            if (distance < nearestDistance) {
                nearest = flight;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private double[] intersection(Flight a, Flight b) {
        double adx = Math.sin(Math.toRadians(a.heading));
        double ady = -Math.cos(Math.toRadians(a.heading));
        double bdx = Math.sin(Math.toRadians(b.heading));
        double bdy = -Math.cos(Math.toRadians(b.heading));
        double determinant = adx * bdy - ady * bdx;
        if (Math.abs(determinant) > .001) {
            double t = ((b.x - a.x) * bdy - (b.y - a.y) * bdx) / determinant;
            if (t >= 0 && t < 1) return new double[]{a.x + adx * t, a.y + ady * t};
        }
        return new double[]{(a.x + b.x) / 2, (a.y + b.y) / 2};
    }

    private double screenX(double normalized) {
        return canvas.getWidth() / 2 + (normalized - .5) * worldWidth();
    }

    private double screenY(double normalized) {
        return canvas.getHeight() / 2 + (normalized - .5) * worldHeight();
    }

    private double worldWidth() {
        return Math.max(1, canvas.getWidth() - 72) * BASE_RANGE / range;
    }

    private double worldHeight() {
        return Math.max(1, canvas.getHeight() - 70) * BASE_RANGE / range;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
