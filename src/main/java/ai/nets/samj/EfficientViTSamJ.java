/*-
 * #%L
 * Library to call models of the family of SAM (Segment Anything Model) from Java
 * %%
 * Copyright (C) 2024 SAMJ developers.
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package ai.nets.samj;

import java.lang.AutoCloseable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.io.File;
import java.io.IOException;

import io.bioimage.modelrunner.apposed.appose.Environment;
import io.bioimage.modelrunner.apposed.appose.Service;
import io.bioimage.modelrunner.apposed.appose.Service.Task;
import io.bioimage.modelrunner.apposed.appose.Service.TaskStatus;

import io.bioimage.modelrunner.tensor.shm.SharedMemoryArray;
import io.bioimage.modelrunner.utils.CommonUtils;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.RealTypeConverters;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.util.Cast;
import net.imglib2.util.Intervals;
import net.imglib2.util.Util;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;

/**
 * Class that provides the needed methods to run EfficientViTSAM models from Java
 * 
 * @author Carlos Javier Garcia Lopez de Haro
 * @author Vladimir Ulman
 */
public class EfficientViTSamJ extends AbstractSamJ implements AutoCloseable {
	/**
	 * Instance of a Python environment {@link Environment}, it is used to initialize Python instances
	 * from that environment
	 */
	private final Environment env;
	/**
	 * Instance of a Python process
	 */
	private final Service python;
	/**
	 * Python script that is going to be run on a Python process
	 */
	private String script = "";
	/**
	 * Instance of a Shared Memory array used to share an object between Python and Java processes
	 */
	private SharedMemoryArray shma;
	/**
	 * Target dimensions of the image that is going to be encoded. If a single-channel 2D image is provided, that image is
	 * converted into a 3-channel image that EfficientViTSAM requires
	 */
	private long[] targetDims;
	/**
	 * Coordinates of the vertex of the crop/zoom of hte image of interest that has been encoded.
	 * It is the closest vertex to the origin.
	 * Usually the vertex is at 0,0 and the encoded image is all the pixels. This feature is useful for when the image 
	 * is big and reeconding needs to happen while the user pans and zooms in the image.
	 */
	private long[] encodeCoords;
	/**
	 * Scale factor of x and y applied to the image that is going to be annotated. 
	 * The image of interest does not need to be encoded normally. However, it is optimal to scale big images
	 * as the resolution of the segmentation depends on the ratio between the size of the image and the size of
	 * the object, thus 
	 */
	private double[] scale;
	/**
	 * Complete image being annotated. Usually this image is encoded completely 
	 * but for larger images, zooms of it might be encoded instead of the whole image
	 */
	private RandomAccessibleInterval<?> img;
	/**
	 * Map that associates the key for each of the existing EfficientViTSAM models to its complete name
	 */
	private static final HashMap<String, String> MODELS_DICT = new HashMap<String, String>();
	static {
		MODELS_DICT.put("l0", "efficientvit_sam_l0");
		MODELS_DICT.put("l1", "efficientvit_sam_l1");
		MODELS_DICT.put("l2", "efficientvit_sam_l2");
		MODELS_DICT.put("xl0", "efficientvit_sam_xl0");
		MODELS_DICT.put("xl1", "efficientvit_sam_xl1");
	}
	/**
	 * All the Python imports and configurations needed to start using EfficientViTSAM.
	 */
	public static final String IMPORTS = ""
			+ "task.update('start')" + System.lineSeparator()
			+ "import numpy as np" + System.lineSeparator()
			+ "from skimage import measure" + System.lineSeparator()
			+ "measure.label(np.ones((10, 10)), connectivity=1)" + System.lineSeparator()
			+ "import torch" + System.lineSeparator()
			+ "import sys" + System.lineSeparator()
			+ "import os" + System.lineSeparator()
			+ "os.chdir(r'%s')" + System.lineSeparator()
			+ "from multiprocessing import shared_memory" + System.lineSeparator()
			+ "task.update('import sam')" + System.lineSeparator()
			+ "from efficientvit.models.efficientvit import EfficientViTSam, %s" + System.lineSeparator()
			+ "from efficientvit.models.efficientvit.sam import EfficientViTSamPredictor" + System.lineSeparator()
			+ "task.update('imported')" + System.lineSeparator()
			+ "" + System.lineSeparator()
			+ "model = %s().cpu().eval()" + System.lineSeparator()
			+ "eps = 1e-6" + System.lineSeparator()
			+ "for m in model.modules():" + System.lineSeparator()
			+ "  if isinstance(m, (torch.nn.GroupNorm, torch.nn.LayerNorm, torch.nn.modules.batchnorm._BatchNorm)):" + System.lineSeparator()
			+ "    if eps is not None:" + System.lineSeparator()
			+ "      m.eps = eps" + System.lineSeparator()
			+ "f_name = os.path.realpath(os.path.expanduser(r'%s'))" + System.lineSeparator()
			+ "weight = torch.load(f_name, map_location='cpu')" + System.lineSeparator()
			+ "if \"state_dict\" in weight:" + System.lineSeparator()
			+ "  weight = weight[\"state_dict\"]" + System.lineSeparator()
			+ "model.load_state_dict(weight)" + System.lineSeparator()
			+ "predictor = EfficientViTSamPredictor(model)" + System.lineSeparator()
			+ "task.update('created predictor')" + System.lineSeparator()
			+ "globals()['shared_memory'] = shared_memory" + System.lineSeparator()
			+ "globals()['measure'] = measure" + System.lineSeparator()
			+ "globals()['np'] = np" + System.lineSeparator()
			+ "globals()['torch'] = torch" + System.lineSeparator()
			+ "globals()['predictor'] = predictor" + System.lineSeparator();
	/**
	 * String containing the Python imports code after it has been formatted with the correct 
	 * paths and names
	 */
	private String IMPORTS_FORMATED;

	/**
	 * Create an instance of the class to be able to run EfficientViTSAM in Java.
	 * 
	 * @param manager
	 * 	environment manager that contians all the paths to the environments needed, Python executables and model weights
	 * @param type
	 * 	EfficientViTSAM model type that we want to use, it can be "l0", "l1", "l2", "xl1" or "xl2"
	 * @throws IOException if any of the files to create a Python process is missing
	 * @throws RuntimeException if there is any error running the Python code
	 * @throws InterruptedException if the process is interrupted
	 */
	private EfficientViTSamJ(SamEnvManager manager, String type) throws IOException, RuntimeException, InterruptedException {
		this(manager, type, (t) -> {}, false);
	}

	/**
	 * Create an instance of the class to be able to run EfficientViTSAM in Java.
	 * 
	 * @param manager
	 * 	environment manager that contians all the paths to the environments needed, Python executables and model weights
	 * @param type
	 * 	EfficientViTSAM model type that we want to use, it can be "l0", "l1", "l2", "xl1" or "xl2"
	 * @param debugPrinter
	 * 	functional interface to redirect the Python process Appose text log and ouptut to be redirected anywhere
	 * @param printPythonCode
	 * 	whether to print the Python code that is going to be executed on the Python process or not
	 * @throws IOException if any of the files to create a Python process is missing
	 * @throws RuntimeException if there is any error running the Python code
	 * @throws InterruptedException if the process is interrupted
	 * 
	 */
	private EfficientViTSamJ(SamEnvManager manager, String type,
	                      final DebugTextPrinter debugPrinter,
	                      final boolean printPythonCode) throws IOException, RuntimeException, InterruptedException {

		if (!MODELS_DICT.keySet().contains(type))
			throw new IllegalArgumentException("The model type should be one of hte following: " 
							+ MODELS_DICT.keySet().stream().collect(Collectors.toList()));
		this.debugPrinter = debugPrinter;
		this.isDebugging = printPythonCode;

		this.env = new Environment() {
			@Override public String base() { return manager.getEfficientViTSamEnv(); }
			@Override public boolean useSystemPath() { return false; }
			};
		python = env.python();
		python.debug(debugPrinter::printText);
		IMPORTS_FORMATED = String.format(IMPORTS,
									manager.getEfficientViTSamEnv() + File.separator + SamEnvManager.EVITSAM_NAME,
									MODELS_DICT.get(type), MODELS_DICT.get(type), manager.getEfficientViTSAMWeightsPath(type));
		
		printScript(IMPORTS_FORMATED + PythonMethods.TRACE_EDGES, "Edges tracing code");
		Task task = python.task(IMPORTS_FORMATED + PythonMethods.TRACE_EDGES);
		System.out.println(IMPORTS_FORMATED + PythonMethods.TRACE_EDGES);
		task.waitFor();
		if (task.status == TaskStatus.CANCELED)
			throw new RuntimeException();
		else if (task.status == TaskStatus.FAILED)
			throw new RuntimeException();
		else if (task.status == TaskStatus.CRASHED)
			throw new RuntimeException();
	}

	/**
	 * Create an EfficientViTSamJ instance that allows to use EfficientViTSAM on an image.
	 * This method encodes the image provided, so depending on the computer and on the model
	 * it might take some time
	 * 
	 * @param <T>
	 * 	the ImgLib2 data type of the image provided
	 * @param modelType
	 * 	EfficientViTSAM model type that we want to use, it can be "l0", "l1", "l2", "xl1" or "xl2"
	 * @param manager
	 * 	environment manager that contians all the paths to the environments needed, Python executables and model weights
	 * @param image
	 * 	the image where SAM is going to be run on
	 * @param debugPrinter
	 * 	functional interface to redirect the Python process Appose text log and ouptut to be redirected anywhere
	 * @param printPythonCode
	 * 	whether to print the Python code that is going to be executed on the Python process or not
	 * @return an instance of {@link EfficientViTSamJ} that allows running EfficienTViTSAM on an image
	 * 	with the image already encoded
	 * @throws IOException if any of the files to create a Python process is missing
	 * @throws RuntimeException if there is any error running the Python code
	 * @throws InterruptedException if the process is interrupted
	 */
	public static <T extends RealType<T> & NativeType<T>> EfficientViTSamJ
	initializeSam(String modelType, SamEnvManager manager,
	              RandomAccessibleInterval<T> image,
	              final DebugTextPrinter debugPrinter,
	              final boolean printPythonCode) throws IOException, RuntimeException, InterruptedException {
		EfficientViTSamJ sam = null;
		try{
			sam = new EfficientViTSamJ(manager, modelType, debugPrinter, printPythonCode);
			sam.encodeCoords = new long[] {0, 0};
			sam.addImage(image);
		} catch (IOException | RuntimeException | InterruptedException ex) {
			if (sam != null) sam.close();
			throw ex;
		}
		return sam;
	}

	/**
	 * Create an EfficientViTSamJ instance that allows to use EfficientViTSAM on an image.
	 * This method encodes the image provided, so depending on the computer and on the model
	 * it might take some time
	 * 
	 * @param <T>
	 * 	the ImgLib2 data type of the image provided
	 * @param modelType
	 * 	EfficientViTSAM model type that we want to use, it can be "l0", "l1", "l2", "xl1" or "xl2"
	 * @param manager
	 * 	environment manager that contians all the paths to the environments needed, Python executables and model weights
	 * @param image
	 * 	the image where SAM is going to be run on
	 * @return an instance of {@link EfficientViTSamJ} that allows running EfficienTViTSAM on an image
	 * @throws IOException if any of the files to create a Python process is missing
	 * @throws RuntimeException if there is any error running the Python code
	 * @throws InterruptedException if the process is interrupted
	 */
	public static <T extends RealType<T> & NativeType<T>> EfficientViTSamJ
	initializeSam(String modelType, SamEnvManager manager, RandomAccessibleInterval<T> image) 
				throws IOException, RuntimeException, InterruptedException {
		EfficientViTSamJ sam = null;
		try{
			sam = new EfficientViTSamJ(manager, modelType);
			sam.encodeCoords = new long[] {0, 0};
			sam.addImage(image);
		} catch (IOException | RuntimeException | InterruptedException ex) {
			if (sam != null) sam.close();
			throw ex;
		}
		return sam;
	}

	/**
	 * Create an EfficientViTSamJ instance that allows to use EfficientViTSAM on an image.
	 * This method encodes the image provided, so depending on the computer and on the model
	 * it might take some time.
	 * 
	 * The model used is the default one {@value SamEnvManager#DEFAULT_EVITSAM}
	 * 
	 * @param <T>
	 * 	the ImgLib2 data type of the image provided
	 * @param manager
	 * 	environment manager that contians all the paths to the environments needed, Python executables and model weights
	 * @param image
	 * 	the image where SAM is going to be run on
	 * @param debugPrinter
	 * 	functional interface to redirect the Python process Appose text log and ouptut to be redirected anywhere
	 * @param printPythonCode
	 * 	whether to print the Python code that is going to be executed on the Python process or not
	 * @return an instance of {@link EfficientViTSamJ} that allows running EfficienTViTSAM on an image
	 * 	with the image already encoded
	 * @throws IOException if any of the files to create a Python process is missing
	 * @throws RuntimeException if there is any error running the Python code
	 * @throws InterruptedException if the process is interrupted
	 */
	public static <T extends RealType<T> & NativeType<T>> EfficientViTSamJ
	initializeSam(SamEnvManager manager,
	              RandomAccessibleInterval<T> image,
	              final DebugTextPrinter debugPrinter,
	              final boolean printPythonCode) throws IOException, RuntimeException, InterruptedException {
		return initializeSam(SamEnvManager.DEFAULT_EVITSAM, manager, image, debugPrinter, printPythonCode);
	}

	/**
	 * Create an EfficientViTSamJ instance that allows to use EfficientViTSAM on an image.
	 * This method encodes the image provided, so depending on the computer and on the model
	 * it might take some time.
	 * 
	 * The model used is the default one {@value SamEnvManager#DEFAULT_EVITSAM}
	 * 
	 * @param <T>
	 * 	the ImgLib2 data type of the image provided
	 * @param manager
	 * 	environment manager that contians all the paths to the environments needed, Python executables and model weights
	 * @param image
	 * 	the image where SAM is going to be run on
	 * @return an instance of {@link EfficientViTSamJ} that allows running EfficienTViTSAM on an image
	 * 	with the image already encoded
	 * @throws IOException if any of the files to create a Python process is missing
	 * @throws RuntimeException if there is any error running the Python code
	 * @throws InterruptedException if the process is interrupted
	 */
	public static <T extends RealType<T> & NativeType<T>> EfficientViTSamJ
	initializeSam(SamEnvManager manager, RandomAccessibleInterval<T> image) throws IOException, RuntimeException, InterruptedException {
		return initializeSam(SamEnvManager.DEFAULT_EVITSAM, manager, image);
	}
	
	/**
	 * Change the image encoded by the EfficientViTSAM model
	 * @param <T>
	 * 	ImgLib2 data type of the image of interest
	 * @param rai
	 * 	image (n-dimensional array) that is going to be encoded as a {@link RandomAccessibleInterval}
	 * @throws IOException if any of the files to run a Python process is missing
	 * @throws RuntimeException if there is any error running the Python code
	 * @throws InterruptedException if the process is interrupted
	 */
	public <T extends RealType<T> & NativeType<T>>
	void updateImage(RandomAccessibleInterval<T> rai) throws IOException, RuntimeException, InterruptedException {
		addImage(rai);
	}
	
	/**
	 * Encode an image (n-dimensional array) with an EfficientViTSAM model
	 * @param <T>
	 * 	ImgLib2 data type of the image of interest
	 * @param rai
	 * 	image (n-dimensional array) that is going to be encoded as a {@link RandomAccessibleInterval}
	 * @throws IOException if any of the files to run a Python process is missing
	 * @throws RuntimeException if there is any error running the Python code
	 * @throws InterruptedException if the process is interrupted
	 */
	private <T extends RealType<T> & NativeType<T>>
	void addImage(RandomAccessibleInterval<T> rai) 
			throws IOException, RuntimeException, InterruptedException {
		if (rai.dimensionsAsLongArray()[0] * rai.dimensionsAsLongArray()[1] > MAX_ENCODED_AREA_RS * MAX_ENCODED_AREA_RS
				|| rai.dimensionsAsLongArray()[0] > MAX_ENCODED_SIDE || rai.dimensionsAsLongArray()[1] > MAX_ENCODED_SIDE) {
			this.targetDims = new long[] {0, 0, 0};
			this.img = rai;
			return;
		}
		this.script = "";
		sendImgLib2AsNp(rai);
		this.script += ""
				+ "task.update(str(im.shape))" + System.lineSeparator()
				+ "predictor.set_image(im)";
		try {
			printScript(script, "Creation of initial embeddings");
			Task task = python.task(script);
			task.waitFor();
			if (task.status == TaskStatus.CANCELED)
				throw new RuntimeException();
			else if (task.status == TaskStatus.FAILED)
				throw new RuntimeException();
			else if (task.status == TaskStatus.CRASHED)
				throw new RuntimeException();
			this.shma.close();
		} catch (IOException | InterruptedException | RuntimeException e) {
			try {
				this.shma.close();
			} catch (IOException e1) {
				throw new IOException(e.toString() + System.lineSeparator() + e1.toString());
			}
			throw e;
		}
	}
	
	private void reencodeCrop() throws IOException, InterruptedException, RuntimeException {
		reencodeCrop(null);
	}
	
	private void reencodeCrop(long[] cropSize) throws IOException, InterruptedException, RuntimeException {
		this.script = "";
		sendCropAsNp(cropSize);
		this.script += ""
				+ "task.update(str(im.shape))" + System.lineSeparator()
				+ "predictor.set_image(im)";
		try {
			printScript(script, "Creation of the cropped embeddings");
			Task task = python.task(script);
			task.waitFor();
			if (task.status == TaskStatus.CANCELED)
				throw new RuntimeException();
			else if (task.status == TaskStatus.FAILED)
				throw new RuntimeException();
			else if (task.status == TaskStatus.CRASHED)
				throw new RuntimeException();
			this.shma.close();
		} catch (IOException | InterruptedException | RuntimeException e) {
			try {
				this.shma.close();
			} catch (IOException e1) {
				throw new IOException(e.toString() + System.lineSeparator() + e1.toString());
			}
			throw e;
		}
		
	}
	
	private List<Polygon> processAndRetrieveContours(HashMap<String, Object> inputs) 
			throws IOException, RuntimeException, InterruptedException {
		Map<String, Object> results = null;
		try {
			Task task = python.task(script, inputs);
			task.waitFor();
			if (task.status == TaskStatus.CANCELED)
				throw new RuntimeException();
			else if (task.status == TaskStatus.FAILED)
				throw new RuntimeException();
			else if (task.status == TaskStatus.CRASHED)
				throw new RuntimeException();
			else if (task.status != TaskStatus.COMPLETE)
				throw new RuntimeException();
			else if (task.outputs.get("contours_x") == null)
				throw new RuntimeException();
			else if (task.outputs.get("contours_y") == null)
				throw new RuntimeException();
			results = task.outputs;
		} catch (IOException | InterruptedException | RuntimeException e) {
			try {
				this.shma.close();
			} catch (IOException e1) {
				throw new IOException(e.toString() + System.lineSeparator() + e1.toString());
			}
			throw e;
		}

		final List<List<Number>> contours_x_container = (List<List<Number>>)results.get("contours_x");
		final Iterator<List<Number>> contours_x = contours_x_container.iterator();
		final Iterator<List<Number>> contours_y = ((List<List<Number>>)results.get("contours_y")).iterator();
		final List<Polygon> polys = new ArrayList<>(contours_x_container.size());
		while (contours_x.hasNext()) {
			int[] xArr = contours_x.next().stream().mapToInt(Number::intValue).toArray();
			int[] yArr = contours_y.next().stream().mapToInt(Number::intValue).toArray();
			polys.add( new Polygon(xArr, yArr, xArr.length) );
		}
		return polys;
	}
	
	/**
	 * Method used that runs EfficientViTSAM using a list of points as the prompt. This method runs
	 * the prompt encoder and the EfficientViTSAM decoder only, the image encoder was run when the model
	 * was initialized with the image, thus it is quite fast.
	 * It returns a list of polygons that corresponds to the contours of the masks found by EfficientViTSAM
	 * @param pointsList
	 * 	the list of points that serve as a prompt for EfficientViTSAM. Each point is an int array
	 * 	of length 2, first position is x-axis, second y-axis
	 * @return a list of polygons where each polygon is the contour of a mask that has been found by EfficientViTSAM
	 * @throws IOException if any of the files needed to run the Python script is missing 
	 * @throws RuntimeException if there is any error running the Python process
	 * @throws InterruptedException if the process in interrupted
	 */
	public List<Polygon> processPoints(List<int[]> pointsList)
			throws IOException, RuntimeException, InterruptedException{
		return processPoints(pointsList, true);
	}
	
	/**
	 * Method used that runs EfficientViTSAM using a list of points as the prompt. This method runs
	 * the prompt encoder and the EfficientViTSAM decoder only, the image encoder was run when the model
	 * was initialized with the image, thus it is quite fast.
	 * It returns a list of polygons that corresponds to the contours of the masks found by EfficientViTSAM
	 * @param pointsList
	 * 	the list of points that serve as a prompt for EfficientViTSAM. Each point is an int array
	 * 	of length 2, first position is x-axis, second y-axis
	 * @return a list of polygons where each polygon is the contour of a mask that has been found by EfficientViTSAM
	 * @throws IOException if any of the files needed to run the Python script is missing 
	 * @throws RuntimeException if there is any error running the Python process
	 * @throws InterruptedException if the process in interrupted
	 */
	public List<Polygon> processPoints(List<int[]> pointsList, Rectangle encodingArea)
			throws IOException, RuntimeException, InterruptedException{
		return processPoints(pointsList, encodingArea, true);
	}
	
	/**
	 * Method used that runs EfficientViTSAM using a list of points as the prompt. This method runs
	 * the prompt encoder and the EfficientViTSAM decoder only, the image encoder was run when the model
	 * was initialized with the image, thus it is quite fast.
	 * It returns a list of polygons that corresponds to the contours of the masks found by EfficientViTSAM
	 * @param pointsList
	 * 	the list of points that serve as a prompt for EfficientViTSAM. Each point is an int array
	 * 	of length 2, first position is x-axis, second y-axis
	 * @param returnAll
	 * 	whether to return all the polygons created by EfficientSAM of only the biggest
	 * @return a list of polygons where each polygon is the contour of a mask that has been found by EfficientViTSAM
	 * @throws IOException if any of the files needed to run the Python script is missing 
	 * @throws RuntimeException if there is any error running the Python process
	 * @throws InterruptedException if the process in interrupted
	 */
	public List<Polygon> processPoints(List<int[]> pointsList, boolean returnAll)
			throws IOException, RuntimeException, InterruptedException{
		return processPoints(pointsList, new ArrayList<int[]>(), returnAll);
	}
	
	/**
	 * Method used that runs EfficientViTSAM using a list of points as the prompt. This method runs
	 * the prompt encoder and the EfficientViTSAM decoder only, the image encoder was run when the model
	 * was initialized with the image, thus it is quite fast.
	 * It returns a list of polygons that corresponds to the contours of the masks found by EfficientViTSAM
	 * @param pointsList
	 * 	the list of points that serve as a prompt for EfficientViTSAM. Each point is an int array
	 * 	of length 2, first position is x-axis, second y-axis
	 * @return a list of polygons where each polygon is the contour of a mask that has been found by EfficientViTSAM
	 * @throws IOException if any of the files needed to run the Python script is missing 
	 * @throws RuntimeException if there is any error running the Python process
	 * @throws InterruptedException if the process in interrupted
	 */
	public List<Polygon> processPoints(List<int[]> pointsList, Rectangle encodingArea, boolean returnAll)
			throws IOException, RuntimeException, InterruptedException{
		return processPoints(pointsList, new ArrayList<int[]>(), encodingArea, returnAll);
	}
	
	/**
	 * Method used that runs EfficientViTSAM using a list of points as the prompt. This method also accepts another
	 * list of points as the negative prompt, the points that represent the background class wrt the object of interest. This method runs
	 * the prompt encoder and the EfficientViTSAM decoder only, the image encoder was run when the model
	 * was initialized with the image, thus it is quite fast.
	 * It returns a list of polygons that corresponds to the contours of the masks found by EfficientViTSAM
	 * @param pointsList
	 * 	the list of points that serve as a prompt for EfficientViTSAM. Each point is an int array
	 * 	of length 2, first position is x-axis, second y-axis
	 * @param pointsNegList
	 * 	the list of points that does not point to the instance of interest, but the background
	 * @return a list of polygons where each polygon is the contour of a mask that has been found by EfficientViTSAM
	 * @throws IOException if any of the files needed to run the Python script is missing 
	 * @throws RuntimeException if there is any error running the Python process
	 * @throws InterruptedException if the process in interrupted
	 */
	public List<Polygon> processPoints(List<int[]> pointsList, List<int[]> pointsNegList)
			throws IOException, RuntimeException, InterruptedException {
		return processPoints(pointsList, pointsNegList, true);
	}
	
	/**
	 * Method used that runs EfficientViTSAM using a list of points as the prompt. This method also accepts another
	 * list of points as the negative prompt, the points that represent the background class wrt the object of interest. This method runs
	 * the prompt encoder and the EfficientViTSAM decoder only, the image encoder was run when the model
	 * was initialized with the image, thus it is quite fast.
	 * It returns a list of polygons that corresponds to the contours of the masks found by EfficientViTSAM
	 * @param pointsList
	 * 	the list of points that serve as a prompt for EfficientViTSAM. Each point is an int array
	 * 	of length 2, first position is x-axis, second y-axis
	 * @param pointsNegList
	 * 	the list of points that does not point to the instance of interest, but the background
	 * @return a list of polygons where each polygon is the contour of a mask that has been found by EfficientViTSAM
	 * @throws IOException if any of the files needed to run the Python script is missing 
	 * @throws RuntimeException if there is any error running the Python process
	 * @throws InterruptedException if the process in interrupted
	 */
	public List<Polygon> processPoints(List<int[]> pointsList, List<int[]> pointsNegList, Rectangle encodingArea)
			throws IOException, RuntimeException, InterruptedException {
		return processPoints(pointsList, pointsNegList, encodingArea, true);
	}
	
	/**
	 * Method used that runs EfficientViTSAM using a list of points as the prompt. This method also accepts another
	 * list of points as the negative prompt, the points that represent the background class wrt the object of interest. This method runs
	 * the prompt encoder and the EfficientViTSAM decoder only, the image encoder was run when the model
	 * was initialized with the image, thus it is quite fast.
	 * It returns a list of polygons that corresponds to the contours of the masks found by EfficientViTSAM
	 * @param pointsList
	 * 	the list of points that serve as a prompt for EfficientViTSAM. Each point is an int array
	 * 	of length 2, first position is x-axis, second y-axis
	 * @param pointsNegList
	 * 	the list of points that does not point to the instance of interest, but the background
	 * @param returnAll
	 * 	whether to return all the polygons created by EfficientSAM of only the biggest
	 * @return a list of polygons where each polygon is the contour of a mask that has been found by EfficientViTSAM
	 * @throws IOException if any of the files needed to run the Python script is missing 
	 * @throws RuntimeException if there is any error running the Python process
	 * @throws InterruptedException if the process in interrupted
	 */
	public List<Polygon> processPoints(List<int[]> pointsList, List<int[]> pointsNegList, boolean returnAll)
			throws IOException, RuntimeException, InterruptedException {
		Rectangle rect = new Rectangle();
		rect.x = -1;
		rect.y = -1;
		rect.height = -1;
		rect.width = -1;
		return processPoints(pointsList, pointsNegList, rect, returnAll);
	}
	
	/**
	 * Method used that runs EfficientViTSAM using a list of points as the prompt. This method also accepts another
	 * list of points as the negative prompt, the points that represent the background class wrt the object of interest. This method runs
	 * the prompt encoder and the EfficientViTSAM decoder only, the image encoder was run when the model
	 * was initialized with the image, thus it is quite fast.
	 * It returns a list of polygons that corresponds to the contours of the masks found by EfficientViTSAM
	 * @param pointsList
	 * 	the list of points that serve as a prompt for EfficientViTSAM. Each point is an int array
	 * 	of length 2, first position is x-axis, second y-axis
	 * @param pointsNegList
	 * 	the list of points that does not point to the instance of interest, but the background
	 * @param returnAll
	 * 	whether to return all the polygons created by EfficientSAM of only the biggest
	 * @return a list of polygons where each polygon is the contour of a mask that has been found by EfficientViTSAM
	 * @throws IOException if any of the files needed to run the Python script is missing 
	 * @throws RuntimeException if there is any error running the Python process
	 * @throws InterruptedException if the process in interrupted
	 */
	public List<Polygon> processPoints(List<int[]> pointsList, List<int[]> pointsNegList, 
					Rectangle encodingArea, boolean returnAll) 
					throws IOException, RuntimeException, InterruptedException {
		Objects.requireNonNull(encodingArea, "Third argument cannot be null. Use the method "
				+ "'processPoints(List<int[]> pointsList, List<int[]> pointsNegList, Rectangle zoomedArea, boolean returnAll)'"
				+ " instead");

		if (encodingArea.x == -1) {
			encodingArea = getCurrentlyEncodedArea();
		} else {
			ArrayList<int[]> outsideP = getPointsNotInRect(pointsList, pointsNegList, encodingArea);
			if (outsideP.size() != 0)
				throw new IllegalArgumentException("The Rectangle containing the area to be encoded should "
					+ "contain all the points. Point {x=" + outsideP.get(0)[0] + ", y=" + outsideP.get(0)[1] + "} is out of the region.");
		}
		evaluateReencodingNeeded(pointsList, pointsNegList, encodingArea);
		this.script = "";
		processPointsWithSAM(pointsList.size(), pointsNegList.size(), returnAll);
		HashMap<String, Object> inputs = new HashMap<String, Object>();
		inputs.put("input_points", pointsList);
		inputs.put("input_neg_points", pointsNegList);
		printScript(script, "Points and negative points inference");
		List<Polygon> polys = processAndRetrieveContours(inputs);
		recalculatePolys(polys, encodeCoords);
		debugPrinter.printText("processPoints() obtained " + polys.size() + " polygons");
		return polys;
	}
	
	private ArrayList<int[]> getPointsNotInRect(List<int[]> pointsList, List<int[]> pointsNegList, Rectangle encodingArea) {
		ArrayList<int[]> points = new ArrayList<int[]>();
		ArrayList<int[]> not = new ArrayList<int[]>();
		points.addAll(pointsNegList);
		points.addAll(pointsList);
		for (int[] pp : points) {
			if (!encodingArea.contains(pp[0], pp[1]))
				not.add(pp);
		}
		return not;
	}
	
	public Rectangle getCurrentlyEncodedArea() {
		int xMargin = (int) (targetDims[1] * 0.1);
		int yMargin = (int) (targetDims[0] * 0.1);
		Rectangle alreadyEncoded;
		if (encodeCoords[0] != 0 || encodeCoords[1] != 0 || targetDims[1] != this.img.dimensionsAsLongArray()[1]
				 || targetDims[0] != this.img.dimensionsAsLongArray()[0]) {
			alreadyEncoded = new Rectangle((int) encodeCoords[0] + xMargin / 2, (int) encodeCoords[1] + yMargin / 2, 
					(int) targetDims[1] - xMargin, (int) targetDims[0] - yMargin);
		} else {
			alreadyEncoded = new Rectangle((int) encodeCoords[0], (int) encodeCoords[1], 
					(int) targetDims[1], (int) targetDims[0]);
		}
		return alreadyEncoded;
	}
	
	/**
	 * TODO Explain reencoding logic
	 * TODO Explain reencoding logic
	 * TODO Explain reencoding logic
	 * TODO Explain reencoding logic
	 * TODO Explain reencoding logic
	 * @param pointsList
	 * @param pointsNegList
	 * @param rect
	 * @throws IOException
	 * @throws InterruptedException
	 * @throws RuntimeException
	 */
	private void evaluateReencodingNeeded(List<int[]> pointsList, List<int[]> pointsNegList, Rectangle rect) 
			throws IOException, InterruptedException, RuntimeException {
		Rectangle alreadyEncoded = getCurrentlyEncodedArea();
		Rectangle neededArea = getApproximateAreaNeeded(pointsList, pointsNegList, rect);
		ArrayList<int[]> notInRect = getPointsNotInRect(pointsList, pointsNegList, rect);
		if (alreadyEncoded.x <= rect.x && alreadyEncoded.y <= rect.y 
				&& alreadyEncoded.width + alreadyEncoded.x >= rect.width + rect.x 
				&& alreadyEncoded.height + alreadyEncoded.y >= rect.width + rect.y
				&& alreadyEncoded.width * 0.9 < rect.width && alreadyEncoded.height * 0.9 < rect.height
				&& notInRect.size() == 0) {
			return;
		} else if (notInRect.size() != 0) {
			this.encodeCoords = new long[] {rect.x, rect.y};
			this.reencodeCrop(new long[] {rect.width, rect.height});
		} else if (alreadyEncoded.x <= rect.x && alreadyEncoded.y <= rect.y 
				&& alreadyEncoded.width + alreadyEncoded.x >= rect.width + rect.x 
				&& alreadyEncoded.height + alreadyEncoded.y >= rect.width + rect.y
				&& (alreadyEncoded.width * 0.9 > rect.width || alreadyEncoded.height * 0.9 > rect.height)) {
			this.encodeCoords = new long[] {rect.x, rect.y};
			this.reencodeCrop(new long[] {rect.width, rect.height});
		} else if (alreadyEncoded.x <= neededArea.x && alreadyEncoded.y <= neededArea.y 
				&& alreadyEncoded.width + alreadyEncoded.x >= neededArea.width + neededArea.x 
				&& alreadyEncoded.height + alreadyEncoded.y >= neededArea.width + neededArea.y
				&& alreadyEncoded.width * 0.9 < neededArea.width && alreadyEncoded.height * 0.9 < neededArea.height
				&& notInRect.size() == 0) {
			return;
		} else {
			this.encodeCoords = new long[] {rect.x, rect.y};
			this.reencodeCrop(new long[] {rect.width, rect.height});
		}
	}
	
	private Rectangle getApproximateAreaNeeded(List<int[]> pointsList, List<int[]> pointsNegList, Rectangle focusedArea) {
		ArrayList<int[]> points = new ArrayList<int[]>();
		points.addAll(pointsNegList);
		points.addAll(pointsList);
		int minY = Integer.MAX_VALUE;
		int minX = Integer.MAX_VALUE;
		int maxY = 0;
		int maxX = 0;
		for (int[] pp : points) {
			if (pp[0] < minX)
				minX = pp[0];
			if (pp[0] > maxX)
				maxX = pp[0];
			if (pp[1] < minY)
				minY = pp[1];
			if (pp[1] > maxY)
				maxY = pp[1];
		}
		minX = (int) Math.max(0,  minX - Math.max(focusedArea.width * 0.1, ENCODE_MARGIN));
		minY = (int) Math.max(0,  minY - Math.max(focusedArea.height * 0.1, ENCODE_MARGIN));
		maxX = (int) Math.min(img.dimensionsAsLongArray()[1],  maxX + Math.max(focusedArea.width * 0.1, ENCODE_MARGIN));
		maxY = (int) Math.min(img.dimensionsAsLongArray()[0],  maxY + Math.max(focusedArea.height * 0.1, ENCODE_MARGIN));
		Rectangle rect = new Rectangle();
		rect.x = minX;
		rect.y = minY;
		rect.width = maxX - minY;
		rect.height = maxY - minY;
		return rect;
	}
	
	/**
	 * Method used that runs EfficientViTSAM using a bounding box as the prompt. The bounding box should
	 * be a int array of length 4 of the form [x0, y0, x1, y1].
	 * This method runs the prompt encoder and the EfficientViTSAM decoder only, the image encoder was run when the model
	 * was initialized with the image, thus it is quite fast.
	 * 
	 * @param boundingBox
	 * 	the bounding box that serves as the prompt for EfficientViTSAM
	 * @return a list of polygons where each polygon is the contour of a mask that has been found by EfficientViTSAM
	 * @throws IOException if any of the files needed to run the Python script is missing 
	 * @throws RuntimeException if there is any error running the Python process
	 * @throws InterruptedException if the process in interrupted
	 */
	public List<Polygon> processBox(int[] boundingBox)
			throws IOException, RuntimeException, InterruptedException {
		return processBox(boundingBox, true);
	}
	
	/**
	 * Method used that runs EfficientViTSAM using a bounding box as the prompt. The bounding box should
	 * be a int array of length 4 of the form [x0, y0, x1, y1].
	 * This method runs the prompt encoder and the EfficientViTSAM decoder only, the image encoder was run when the model
	 * was initialized with the image, thus it is quite fast.
	 * 
	 * @param boundingBox
	 * 	the bounding box that serves as the prompt for EfficientViTSAM
	 * @param returnAll
	 * 	whether to return all the polygons created by EfficientSAM of only the biggest
	 * @return a list of polygons where each polygon is the contour of a mask that has been found by EfficientViTSAM
	 * @throws IOException if any of the files needed to run the Python script is missing 
	 * @throws RuntimeException if there is any error running the Python process
	 * @throws InterruptedException if the process in interrupted
	 */
	public List<Polygon> processBox(int[] boundingBox, boolean returnAll)
			throws IOException, RuntimeException, InterruptedException {
		if (needsMoreResolution(boundingBox)) {
			this.encodeCoords = calculateEncodingNewCoords(boundingBox, this.img.dimensionsAsLongArray());
			reencodeCrop();
		} else if (!isAreaEncoded(boundingBox)) {
			this.encodeCoords = calculateEncodingNewCoords(boundingBox, this.img.dimensionsAsLongArray());
			reencodeCrop();
		}
		int[] adaptedBoundingBox = new int[] {(int) (boundingBox[0] - encodeCoords[0]), (int) (boundingBox[1] - encodeCoords[1]),
				(int) (boundingBox[2] - encodeCoords[0]), (int) (boundingBox[3] - encodeCoords[1])};;
		this.script = "";
		processBoxWithSAM(returnAll);
		HashMap<String, Object> inputs = new HashMap<String, Object>();
		inputs.put("input_box", adaptedBoundingBox);
		printScript(script, "Rectangle inference");
		List<Polygon> polys = processAndRetrieveContours(inputs);
		recalculatePolys(polys, encodeCoords);
		debugPrinter.printText("processBox() obtained " + polys.size() + " polygons");
		return polys;
	}
	
	/**
	 * Check whether the bounding box is inside the area that is encoded or not
	 * @param boundingBox
	 * 	the vertices of the bounding box
	 * @return whether the bounding box is within the encoded area or not
	 */
	public boolean isAreaEncoded(int[] boundingBox) {
		boolean upperLeftVertex = (boundingBox[0] > this.encodeCoords[0]) && (boundingBox[0] < this.encodeCoords[2]);
		boolean upperRightVertex = (boundingBox[2] > this.encodeCoords[0]) && (boundingBox[2] < this.encodeCoords[2]);
		boolean downLeftVertex = (boundingBox[1] > this.encodeCoords[1]) && (boundingBox[1] < this.encodeCoords[3]);
		boolean downRightVertex = (boundingBox[3] > this.encodeCoords[1]) && (boundingBox[3] < this.encodeCoords[3]);
		
		if (upperLeftVertex && upperRightVertex && downLeftVertex && downRightVertex)
			return true;
		return false;
	}

	/**
	 * For bounding box masks, check whether the its size is too small compared to the size
	 * of the encoded image.
	 * 
	 * Approximately, if the original image encoded is about 20 times bigger than the bounding box size,
	 * the resolution of the SAM-based model encodings will not be enough to identify the object of interest,
	 * thus re-encoding of a zoomed part of the image will be necessary.
	 * 
	 * @param boundingBox
	 * 	bounding box of interest
	 * @return whether the bounding box of interest is big enough to produce good results or not
	 */
	public boolean needsMoreResolution(int[] boundingBox) {
		long xSize = boundingBox[2] - boundingBox[0];
		long ySize = boundingBox[3] - boundingBox[1];
		long encodedX = targetDims[1];
		long encodedY = targetDims[0];
		if (xSize * LOWER_REENCODE_THRESH < encodedX && ySize * LOWER_REENCODE_THRESH < encodedY)
			return true;
		return false;
	}
	
	/**
	 * TODO what to do, is there a bounding box that is too big with respect to the encoded crop?
	 * @param boundingBox
	 * @return
	 */
	public boolean boundingBoxTooBig(int[] boundingBox) {
		long xSize = boundingBox[2] - boundingBox[0];
		long ySize = boundingBox[3] - boundingBox[1];
		long encodedX = targetDims[1];
		long encodedY = targetDims[0];
		if (xSize * UPPER_REENCODE_THRESH > encodedX && ySize * UPPER_REENCODE_THRESH > encodedY)
			return true;
		return false;
	}


	@Override
	/**
	 * {@inheritDoc}
	 * Close the Python process and clean the memory
	 */
	public void close() {
		if (python != null) python.close();
	}
	
	private <T extends RealType<T> & NativeType<T>> 
	void sendImgLib2AsNp(RandomAccessibleInterval<T> targetImg) {
		shma = createEfficientSAMInputSHM(targetImg);
		adaptImageToModel(targetImg, shma.getSharedRAI());
		String code = "";
		// This line wants to recreate the original numpy array. Should look like:
		// input0_appose_shm = shared_memory.SharedMemory(name=input0)
		// input0 = np.ndarray(size, dtype="float64", buffer=input0_appose_shm.buf).reshape([64, 64])
		code += "im_shm = shared_memory.SharedMemory(name='"
							+ shma.getNameForPython() + "', size=" + shma.getSize() 
							+ ")" + System.lineSeparator();
		int size = 1;
		for (long l : targetDims) {size *= l;}
		code += "im = np.ndarray(" + size + ", dtype='uint8', buffer=im_shm.buf).reshape([";
		for (long ll : targetDims)
			code += ll + ", ";
		code = code.substring(0, code.length() - 2);
		code += "])" + System.lineSeparator();
		//code += "np.save('/home/carlos/git/aa.npy', im)" + System.lineSeparator();
		code += "im_shm.unlink()" + System.lineSeparator();
		//code += "box_shm.close()" + System.lineSeparator();
		this.script += code;
	}
	
	private <T extends RealType<T> & NativeType<T>> 
	void sendCropAsNp() {
		sendCropAsNp(null);
	}
		
	private <T extends RealType<T> & NativeType<T>> void sendCropAsNp(long[] cropSize) {
		if (cropSize == null)
			cropSize = new long[] {encodeCoords[3] - encodeCoords[1], encodeCoords[2] - encodeCoords[0], 3};
		else if (cropSize.length == 2)
			cropSize = new long[] {cropSize[1], cropSize[0], 3};
		RandomAccessibleInterval<T> crop = 
				Views.interval( Cast.unchecked(img), new long[] {encodeCoords[1], encodeCoords[0], 0}, cropSize );
		
		crop = Views.offsetInterval(crop, new long[] {encodeCoords[1], encodeCoords[0], 0}, cropSize);
		//RandomAccessibleInterval<T> crop = Views.offsetInterval(Cast.unchecked(img), new long[] {encodeCoords[1], encodeCoords[0], 0}, cropSize);
		targetDims = crop.dimensionsAsLongArray();
		shma = SharedMemoryArray.buildMemorySegmentForImage(new long[] {targetDims[0], targetDims[1], targetDims[2]}, 
															Util.getTypeFromInterval(crop));
		RealTypeConverters.copyFromTo(crop, shma.getSharedRAI());
		String code = "";
		// This line wants to recreate the original numpy array. Should look like:
		// input0_appose_shm = shared_memory.SharedMemory(name=input0)
		// input0 = np.ndarray(size, dtype="float64", buffer=input0_appose_shm.buf).reshape([64, 64])
		code += "im_shm = shared_memory.SharedMemory(name='"
							+ shma.getNameForPython() + "', size=" + shma.getSize() 
							+ ")" + System.lineSeparator();
		int size = 1;
		for (long l : targetDims) {size *= l;}
		code += "im = np.ndarray(" + size + ", dtype='" + CommonUtils.getDataType(Util.getTypeFromInterval(crop)) + "', buffer=im_shm.buf).reshape([";
		for (long ll : targetDims)
			code += ll + ", ";
		code = code.substring(0, code.length() - 2);
		code += "])" + System.lineSeparator();
		code += "input_h = im.shape[0]" + System.lineSeparator();
		code += "input_w = im.shape[1]" + System.lineSeparator();
		//code += "np.save('/home/carlos/git/cropped.npy', im)" + System.lineSeparator();
		code += "globals()['input_h'] = input_h" + System.lineSeparator();
		code += "globals()['input_w'] = input_w" + System.lineSeparator();
		code += "im = torch.from_numpy(np.transpose(im.astype('float32'), (2, 0, 1)))" + System.lineSeparator();
		code += "im_shm.unlink()" + System.lineSeparator();
		//code += "box_shm.close()" + System.lineSeparator();
		this.script += code;
	}
	
	/**
	 * Method used that runs EfficientViTSAM using a mask as the prompt. The mask should be a 2D single-channel
	 * image {@link RandomAccessibleInterval} of the same x and y sizes as the image of interest, the image 
	 * where the model is finding the segmentations.
	 * Note that the quality of this prompting method is not good, it is still experimental as it barely works.
	 * It returns a list of polygons that corresponds to the contours of the masks found by EfficientViTSAM.
	 * 
	 * @param <T>
	 * 	ImgLib2 datatype of the mask
	 * @param img
	 * 	mask used as the prompt
	 * @return a list of polygons where each polygon is the contour of a mask that has been found by EfficientViTSAM
	 * @throws IOException if any of the files needed to run the Python script is missing 
	 * @throws RuntimeException if there is any error running the Python process
	 * @throws InterruptedException if the process in interrupted
	 */
	public <T extends RealType<T> & NativeType<T>>
	List<Polygon> processMask(RandomAccessibleInterval<T> img)
			throws IOException, RuntimeException, InterruptedException {
		return processMask(img, true);
	}
	
	/**
	 * Method used that runs EfficientViTSAM using a mask as the prompt. The mask should be a 2D single-channel
	 * image {@link RandomAccessibleInterval} of the same x and y sizes as the image of interest, the image 
	 * where the model is finding the segmentations.
	 * Note that the quality of this prompting method is not good, it is still experimental as it barely works
	 * 
	 * @param <T>
	 * 	ImgLib2 datatype of the mask
	 * @param img
	 * 	mask used as the prompt
	 * @param returnAll
	 * 	whether to return all the polygons created by EfficientSAM of only the biggest
	 * @return a list of polygons where each polygon is the contour of a mask that has been found by EfficientViTSAM
	 * @throws IOException if any of the files needed to run the Python script is missing 
	 * @throws RuntimeException if there is any error running the Python process
	 * @throws InterruptedException if the process in interrupted
	 */
	public <T extends RealType<T> & NativeType<T>>
	List<Polygon> processMask(RandomAccessibleInterval<T> img, boolean returnAll)
			throws IOException, RuntimeException, InterruptedException {
		long[] dims = img.dimensionsAsLongArray();
		if (dims.length == 2 && dims[1] == this.shma.getOriginalShape()[1] && dims[0] == this.shma.getOriginalShape()[0]) {
			img = Views.permute(img, 0, 1);
		} else if (dims.length != 2 && dims[0] != this.shma.getOriginalShape()[1] && dims[1] != this.shma.getOriginalShape()[0]) {
			throw new IllegalArgumentException("The provided mask should be a 2d image with just one channel of width "
					+ this.shma.getOriginalShape()[1] + " and height " + this.shma.getOriginalShape()[0]);
		}
		SharedMemoryArray maskShma = SharedMemoryArray.buildSHMA(img);
		try {
			return processMask(maskShma, returnAll);
		} catch (IOException | RuntimeException | InterruptedException ex) {
			maskShma.close();
			throw ex;
		}
	}
	
	private List<Polygon> processMask(SharedMemoryArray shmArr, boolean returnAll) throws IOException, RuntimeException, InterruptedException {
		this.script = "";
		processMasksWithSam(shmArr, returnAll);
		printScript(script, "Pre-computed mask inference");
		List<Polygon> polys = processAndRetrieveContours(null);
		debugPrinter.printText("processMask() obtained " + polys.size() + " polygons");
		return polys;
	}
	
	private void processMasksWithSam(SharedMemoryArray shmArr, boolean returnAll) {
		String code = "";
		code += "shm_mask = shared_memory.SharedMemory(name='" + shmArr.getNameForPython() + "')" + System.lineSeparator();
		code += "mask = np.frombuffer(buffer=shm_mask.buf, dtype='" + shmArr.getOriginalDataType() + "').reshape([";
		for (long l : shmArr.getOriginalShape()) 
			code += l + ",";
		code += "])" + System.lineSeparator();
		code += "different_mask_vals = np.unique(mask)" + System.lineSeparator();
		code += "contours_x = []" + System.lineSeparator();
		code += "contours_y = []" + System.lineSeparator();
		code += "for val in different_mask_vals:" + System.lineSeparator()
			  + "  if val < 1:" + System.lineSeparator()
			  + "    continue" + System.lineSeparator()
			  + "  locations = np.where(mask == val)" + System.lineSeparator()
			  + "  input_points_pos = np.zeros((locations[0].shape[0], 2))" + System.lineSeparator()
			  + "  input_labels_pos = np.ones((locations[0].shape[0]))" + System.lineSeparator()
			  + "  locations_neg = np.where((mask != val) & (mask != 0))" + System.lineSeparator()
			  + "  input_points_neg = np.zeros((locations_neg[0].shape[0], 2))" + System.lineSeparator()
			  + "  input_labels_neg = np.zeros((locations_neg[0].shape[0]))" + System.lineSeparator()
			  + "  input_points_pos[:, 0] = locations[0]" + System.lineSeparator()
			  + "  input_points_pos[:, 1] = locations[1]" + System.lineSeparator()
			  + "  input_points_neg[:, 0] = locations_neg[0]" + System.lineSeparator()
			  + "  input_points_neg[:, 1] = locations_neg[1]" + System.lineSeparator()
			  + "  input_points = np.concatenate((input_points_pos.reshape(-1, 2), input_points_neg.reshape(-1, 2)), axis=0)" + System.lineSeparator()
			  + "  input_label = np.concatenate((input_labels_pos, input_labels_neg), axis=0)" + System.lineSeparator()
			  + "  mask_val, _, _ = predictor.predict(" + System.lineSeparator()
			  + "    point_coords=input_points," + System.lineSeparator()
			  + "    point_labels=input_label," + System.lineSeparator()
			  + "    multimask_output=False," + System.lineSeparator()
			  + "    box=None,)" + System.lineSeparator()
			  //+ "np.save('/temp/aa.npy', mask)" + System.lineSeparator()
			  + "  contours_x_val,contours_y_val = get_polygons_from_binary_mask(mask_val[0], only_biggest=" + (!returnAll ? "True" : "False") + ")" + System.lineSeparator()
			  + "  contours_x += contours_x_val" + System.lineSeparator()
			  + "  contours_y += contours_y_val" + System.lineSeparator()
			  + "task.update('all contours traced')" + System.lineSeparator()
			  + "task.outputs['contours_x'] = contours_x" + System.lineSeparator()
			  + "task.outputs['contours_y'] = contours_y" + System.lineSeparator();
		code += "mask = 0" + System.lineSeparator();
		code += "shm_mask.close()" + System.lineSeparator();
		code += "shm_mask.unlink()" + System.lineSeparator();
		this.script = code;
	}
	
	private void processPointsWithSAM(int nPoints, int nNegPoints, boolean returnAll) {
		String code = "" + System.lineSeparator()
				+ "task.update('start predict')" + System.lineSeparator()
				+ "input_points_list = []" + System.lineSeparator()
				+ "input_neg_points_list = []" + System.lineSeparator();
		for (int n = 0; n < nPoints; n ++)
			code += "input_points_list.append([input_points[" + n + "][0], input_points[" + n + "][1]])" + System.lineSeparator();
		for (int n = 0; n < nNegPoints; n ++)
			code += "input_neg_points_list.append([input_neg_points[" + n + "][0], input_neg_points[" + n + "][1]])" + System.lineSeparator();
		code += ""
				+ "input_points = np.concatenate("
						+ "(np.array(input_points_list).reshape(" + nPoints + ", 2), np.array(input_neg_points_list).reshape(" + nNegPoints + ", 2))"
						+ ", axis=0)" + System.lineSeparator()
				+ "input_label = np.array([1] * " + (nPoints + nNegPoints) + ")" + System.lineSeparator()
				+ "input_label[" + nPoints + ":] -= 1" + System.lineSeparator()
				+ "mask, _, _ = predictor.predict(" + System.lineSeparator()
				+ "    point_coords=input_points," + System.lineSeparator()
				+ "    point_labels=input_label," + System.lineSeparator()
				+ "    multimask_output=False," + System.lineSeparator()
				+ "    box=None,)" + System.lineSeparator()
				+ "task.update('end predict')" + System.lineSeparator()
				+ "task.update(str(mask.shape))" + System.lineSeparator()
				//+ "np.save('/temp/aa.npy', mask)" + System.lineSeparator()
				+ "contours_x,contours_y = get_polygons_from_binary_mask(mask[0], only_biggest=" + (!returnAll ? "True" : "False") + ")" + System.lineSeparator()
				+ "task.update('all contours traced')" + System.lineSeparator()
				+ "task.outputs['contours_x'] = contours_x" + System.lineSeparator()
				+ "task.outputs['contours_y'] = contours_y" + System.lineSeparator();
		this.script = code;
	}
	
	private void processBoxWithSAM(boolean returnAll) {
		String code = "" + System.lineSeparator()
				+ "task.update('start predict')" + System.lineSeparator()
				+ "input_box = np.array([[input_box[0], input_box[1]], [input_box[2], input_box[3]]])" + System.lineSeparator()
				+ "mask, _, _ = predictor.predict(" + System.lineSeparator()
				+ "    point_coords=None," + System.lineSeparator()
				+ "    point_labels=None," + System.lineSeparator()
				+ "    multimask_output=False," + System.lineSeparator()
				+ "    box=input_box,)" + System.lineSeparator()
				+ "task.update('end predict')" + System.lineSeparator()
				+ "task.update(str(mask.shape))" + System.lineSeparator()
				//+ "np.save('/home/carlos/git/mask.npy', mask)" + System.lineSeparator()
				+ "contours_x,contours_y = get_polygons_from_binary_mask(mask[0], only_biggest=" + (!returnAll ? "True" : "False") + ")" + System.lineSeparator()
				+ "task.update('all contours traced')" + System.lineSeparator()
				+ "task.outputs['contours_x'] = contours_x" + System.lineSeparator()
				+ "task.outputs['contours_y'] = contours_y" + System.lineSeparator();
		this.script = code;
	}
	
	private static <T extends RealType<T> & NativeType<T>>
	SharedMemoryArray  createEfficientSAMInputSHM(final RandomAccessibleInterval<T> inImg) {
		long[] dims = inImg.dimensionsAsLongArray();
		if ((dims.length != 3 && dims.length != 2) || (dims.length == 3 && dims[2] != 3 && dims[2] != 1)) {
			throw new IllegalArgumentException("Currently SAMJ only supports 1-channel (grayscale) or 3-channel (RGB, BGR, float32, ...) 2D images."
					+ "The image dimensions order should be 'xyc', first dimension height, second width and third channels.");
		}
		return SharedMemoryArray.buildMemorySegmentForImage(new long[] {dims[0], dims[1], 3}, new UnsignedByteType());
	}
	
	private <T extends RealType<T> & NativeType<T>>
	void adaptImageToModel(final RandomAccessibleInterval<T> ogImg, RandomAccessibleInterval<UnsignedByteType> targetImg) {
		if (ogImg.numDimensions() == 3 && ogImg.dimensionsAsLongArray()[2] == 3) {
			for (int i = 0; i < 3; i ++) 
				RealTypeConverters.copyFromTo( convertViewToRGB(Views.hyperSlice(ogImg, 2, i)), Views.hyperSlice(targetImg, 2, i) );
		} else if (ogImg.numDimensions() == 3 && ogImg.dimensionsAsLongArray()[2] == 1) {
			debugPrinter.printText("CONVERTED 1 CHANNEL IMAGE INTO 3 TO BE FEEDED TO SAMJ");
			IntervalView<UnsignedByteType> resIm = Views.interval( Views.expandMirrorDouble(convertViewToRGB(ogImg), new long[] {0, 0, 2}), 
					Intervals.createMinMax(new long[] {0, 0, 0, ogImg.dimensionsAsLongArray()[0], ogImg.dimensionsAsLongArray()[1], 2}) );
			RealTypeConverters.copyFromTo( resIm, targetImg );
		} else if (ogImg.numDimensions() == 2) {
			adaptImageToModel(Views.addDimension(ogImg, 0, 0), targetImg);
		} else {
			throw new IllegalArgumentException("Currently SAMJ only supports 1-channel (grayscale) or 3-channel (RGB, BGR, ...) 2D images."
					+ "The image dimensions order should be 'yxc', first dimension height, second width and third channels.");
		}
		this.img = targetImg;
		this.targetDims = targetImg.dimensionsAsLongArray();
	}
	
	/**
	 * Tests during development
	 * @param args
	 * 	nothing
	 * @throws IOException nothing
	 * @throws RuntimeException nothing
	 * @throws InterruptedException nothing
	 */
	public static void main(String[] args) throws IOException, RuntimeException, InterruptedException {
		RandomAccessibleInterval<UnsignedByteType> img = ArrayImgs.unsignedBytes(new long[] {50, 50, 3});
		img = Views.addDimension(img, 1, 2);
		try (EfficientViTSamJ sam = initializeSam(SamEnvManager.create(), img)) {
			sam.processBox(new int[] {0, 5, 10, 26});
		}
	}
	
	/**
	 * 
	 * @return the list of EfficientViTSAM models that are supported
	 */
	public static List<String> getListOfSupportedEfficientViTSAM(){
		return MODELS_DICT.keySet().stream().collect(Collectors.toList());
	}
}
