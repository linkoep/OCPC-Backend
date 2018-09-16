package model;

public class Box {
    private Point topLeft;
    private Point bottomRight;
    private Building building;

    public Box(Point topLeft, Point bottomRight, Building building) {
        this.topLeft = topLeft;
        this.bottomRight = bottomRight;
        this.building = building;
    }

    public Box() {
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

    public Building getBuilding() {
        return building;
    }

    public void setBuilding(Building building) {
        this.building = building;
    }
}
