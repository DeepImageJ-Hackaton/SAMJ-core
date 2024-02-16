package ai.nets.samj.ui;

import net.imglib2.RandomAccessibleInterval;

import java.util.List;

import ai.nets.samj.communication.model.SAMModel;

import java.awt.Polygon;

public interface PromptsResultsDisplay {

	/**
	 * Give the display an image on which it shall operate. It is, however,
	 * not expected that the display will operate on the full image. To
	 * get the potential portion of the image on which the display eventually
	 * operates, use {@link PromptsResultsDisplay#giveProcessedSubImage()}.
	 */
	void switchToThisImg(final RandomAccessibleInterval<?> newImage);

	/**
	 * Returns the actual image on which the display operates, which may
	 * very well be only a portion of the originaly provided image, see
	 * {@link PromptsResultsDisplay#switchToThisImg(RandomAccessibleInterval)}.
	 */
	RandomAccessibleInterval<?> giveProcessedSubImage(SAMModel selectedModel);

	void switchToThisNet(final SAMModel promptsToNetAdapter);
	void notifyNetToClose();

	List<Polygon> getPolygonsFromRoiManager();

	void enableAddingToRoiManager(boolean shouldBeAdding);
	boolean isAddingToRoiManager();

	void switchToUsingRectangles();
	void switchToUsingLines();
	void switchToUsingPoints();
	void switchToNone();
	
	Object getFocusedImage();
}