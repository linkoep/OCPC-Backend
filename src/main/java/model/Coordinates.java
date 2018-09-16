package model;

public class Coordinates {

    private Point topLeft;
    private Point bottomRight;

    public Coordinates(int x1, int y1, int x2, int y2) {
        this.topLeft = new Point(x1, y1);
        this.bottomRight = new Point(x2, y2);
    }

    public Coordinates() {
        //JSON Serialization
    }

    public Point getTopLeft() {
        return topLeft;
    }

    public void setTopLeft(Point topLeft) {
        this.topLeft = topLeft;
    }

    public Point getBottomRight() {
        return bottomRight;
    }

    public void setBottomRight(Point bottomRight) {
        this.bottomRight = bottomRight;
    }

    @Override
    public String toString() {
        return "Coordinates{" +
                "topLeft=" + topLeft +
                ", bottomRight=" + bottomRight +
                '}';
    }
}
