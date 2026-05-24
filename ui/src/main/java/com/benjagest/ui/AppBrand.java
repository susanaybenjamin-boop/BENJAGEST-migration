package com.benjagest.ui;

import java.io.InputStream;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;

final class AppBrand {

    private static final String CUSTOM_ICON_RESOURCE = "/com/benjagest/ui/app-icon.png";

    private AppBrand() {
    }

    static StackPane createLogoMark() {
        Circle background = new Circle(25);
        background.getStyleClass().add("logo-background");

        Rectangle tower = new Rectangle(11, 22);
        tower.setTranslateX(-6);
        tower.setTranslateY(7);
        tower.getStyleClass().add("logo-building");

        Rectangle blockTop = new Rectangle(9, 8);
        blockTop.setTranslateX(7);
        blockTop.setTranslateY(0);
        blockTop.getStyleClass().add("logo-building-light");

        Rectangle blockBottom = new Rectangle(9, 11);
        blockBottom.setTranslateX(7);
        blockBottom.setTranslateY(12);
        blockBottom.getStyleClass().add("logo-building");

        Line craneMast = new Line(0, 0, 0, 24);
        craneMast.setTranslateX(-16);
        craneMast.setTranslateY(-3);
        craneMast.getStyleClass().add("logo-line");

        Line craneArm = new Line(0, 0, 30, 0);
        craneArm.setTranslateX(-1);
        craneArm.setTranslateY(-15);
        craneArm.getStyleClass().add("logo-line");

        Line craneCable = new Line(0, 0, 0, 12);
        craneCable.setTranslateX(20);
        craneCable.setTranslateY(-9);
        craneCable.getStyleClass().add("logo-line-light");

        Rectangle hook = new Rectangle(7, 5);
        hook.setTranslateX(20);
        hook.setTranslateY(2);
        hook.getStyleClass().add("logo-hook");

        StackPane logo = new StackPane(background, craneMast, craneArm, craneCable, hook, tower, blockTop, blockBottom);
        logo.getStyleClass().add("logo-mark");
        return logo;
    }

    static Image loadWindowIcon() {
        InputStream customIcon = AppBrand.class.getResourceAsStream(CUSTOM_ICON_RESOURCE);
        if (customIcon != null) {
            return new Image(customIcon);
        }
        return createFallbackIcon();
    }

    private static Image createFallbackIcon() {
        int size = 128;
        Canvas canvas = new Canvas(size, size);
        GraphicsContext graphics = canvas.getGraphicsContext2D();

        graphics.setFill(Color.web("#0aa6a6"));
        graphics.fillOval(8, 8, 112, 112);
        graphics.setFill(Color.web("#2357f6", 0.88));
        graphics.fillOval(8, 8, 112, 112);

        graphics.setStroke(Color.WHITE);
        graphics.setLineWidth(8);
        graphics.strokeLine(32, 34, 32, 88);
        graphics.strokeLine(32, 34, 92, 34);
        graphics.setStroke(Color.web("#d9fbff"));
        graphics.setLineWidth(5);
        graphics.strokeLine(86, 34, 86, 58);

        graphics.setFill(Color.WHITE);
        graphics.fillRoundRect(40, 62, 20, 38, 6, 6);
        graphics.setFill(Color.web("#d9fbff"));
        graphics.fillRoundRect(66, 50, 18, 18, 6, 6);
        graphics.setFill(Color.WHITE);
        graphics.fillRoundRect(66, 74, 18, 26, 6, 6);
        graphics.setFill(Color.web("#f8d348"));
        graphics.fillRoundRect(80, 58, 16, 12, 5, 5);

        WritableImage image = new WritableImage(size, size);
        canvas.snapshot(null, image);
        return image;
    }
}
