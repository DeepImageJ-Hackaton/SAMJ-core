package sc.fiji.samj.gui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JToolBar;

import net.imglib2.view.Views;
import sc.fiji.samj.communication.PromptsToNetAdapter;
import sc.fiji.samj.communication.model.SAMModels;
import sc.fiji.samj.gui.components.GridPanel;
import sc.fiji.samj.gui.icons.ButtonIcon;
import sc.fiji.samj.gui.tools.Tools;
import sc.fiji.samj.ui.PromptsResultsDisplay;
import sc.fiji.samj.ui.SAMJLogger;

public class SAMJDialog extends JDialog implements ActionListener, WindowListener {

	private JButton bnClose = new JButton("Close");
	private JButton bnHelp = new JButton("Help");
	private JButton bnStart = new JButton("Start/Encode");
	private JButton bnStop = new JButton("Stop");
	private JButton bnComplete = new JButton("Auto-Complete");
	private JButton bnRoi2Mask = new JButton("Create Mask");
	private JTextField txtStatus = new JTextField("(c) SAMJ team 2024");

	private ImagePlus imp;
	private ImagePlus mask;
	
	private ButtonIcon bnRect = new ButtonIcon("Rect", "rect.png");
	private ButtonIcon bnPoints = new ButtonIcon("Points", "edit.png");
	private ButtonIcon bnBrush = new ButtonIcon("Brush", "github.png");
	private ButtonIcon bnMask = new ButtonIcon("Mask", "help.png");
	private JCheckBox chkROIManager = new JCheckBox("Add to ROI Manager", true);

	private JComboBox<String> cmbImage = new JComboBox<String>();
	
	private final SAMModelPanel panelModel;
	private final PromptsResultsDisplay display;
	private final SAMJLogger GUIsOwnLog;
	private final SAMJLogger logForNetworks;

	private boolean encodingDone = false;

	public SAMJDialog(final PromptsResultsDisplay display,
	                  final SAMModels availableModel) {
		this(display, availableModel, null, null);
	}

	public SAMJDialog(final PromptsResultsDisplay display,
	                  final SAMModels availableModel,
	                  final SAMJLogger guilogger) {
		this(display, availableModel, guilogger, null);
	}

	public SAMJDialog(final PromptsResultsDisplay display,
	                  final SAMModels availableModel,
	                  final SAMJLogger guilogger,
	                  final SAMJLogger networkLogger) {
		super(new JFrame(), "SAMJ Annotator");
		if (guilogger == null) {
			this.GUIsOwnLog = new SAMJLogger () {
				@Override
				public void info(String text) {}
				@Override
				public void warn(String text) {}
				@Override
				public void error(String text) {}
			};
		} else {
			this.GUIsOwnLog = guilogger;
		}
		if (networkLogger == null) {
			this.logForNetworks = new SAMJLogger () {
				@Override
				public void info(String text) {}
				@Override
				public void warn(String text) {}
				@Override
				public void error(String text) {}
			};
		} else {
			this.logForNetworks = networkLogger;
		}
		this.display = display;

		panelModel = new SAMModelPanel(availableModel);
		// Buttons
		JPanel pnButtons = new JPanel(new FlowLayout());
		pnButtons.add(bnRect);
		pnButtons.add(bnPoints);
		pnButtons.add(bnBrush);
		pnButtons.add(bnMask);
		
		// Status
		JToolBar pnStatus = new JToolBar();
		pnStatus.setFloatable(false);
		pnStatus.setLayout(new BorderLayout());
		pnStatus.add(bnHelp, BorderLayout.EAST);
		pnStatus.add(txtStatus, BorderLayout.CENTER);
		pnStatus.add(bnClose, BorderLayout.WEST);

		JPanel pnActions = new JPanel(new FlowLayout());
		pnActions.add(bnRoi2Mask);
		pnActions.add(bnComplete);
		pnActions.add(chkROIManager);
		
		ArrayList<String> listImages = getListImages();
		for(String nameImage : listImages)
			cmbImage.addItem(nameImage);
	
		GridPanel panelImage = new GridPanel(true);
		panelImage.place(1, 1, 1, 1, bnStart);
		panelImage.place(1, 2, 1, 1, cmbImage);
		
		GridPanel pn = new GridPanel();
		pn.place(1, 1, panelModel);
		pn.place(2, 1, panelImage);
		pn.place(3, 1, pnButtons);
		pn.place(4, 1, pnActions);
		
		setLayout(new BorderLayout());
		add(pn, BorderLayout.NORTH);		
		add(pnStatus, BorderLayout.SOUTH);		

		bnRoi2Mask.addActionListener(this);		
		bnComplete.addActionListener(this);
		bnClose.addActionListener(this);
		bnHelp.addActionListener(this);
		chkROIManager.addActionListener(this);
		
		bnStart.addActionListener(this);
		bnStop.addActionListener(this);
		bnRect.addActionListener(this);
		bnPoints.addActionListener(this);
		bnBrush.addActionListener(this);
		bnMask.setDropTarget(new LocalDropTarget());
		
		add(pn);
		pack();
		this.setResizable(false);
		this.setModal(false);
		this.setVisible(true);
		// TODO remove GUI.center(this);
		updateInterface();

		this.addWindowListener(this);
	}

	@Override
	public void actionPerformed(ActionEvent e) {

		if (e.getSource() == bnRect) {
			display.switchToUsingRectangles();
		}
		if (e.getSource() == bnPoints) {
			display.switchToUsingPoints();
		}
		if (e.getSource() == bnBrush) {
			display.switchToUsingLines();
		}

		if (e.getSource() == bnHelp) {
			Tools.help();
		}
		
		if (e.getSource() == bnClose) {
			display.notifyNetToClose();
			dispose();
		}
		
		if (e.getSource() == bnComplete) {
			GUIsOwnLog.warn("TO DO call Auto-complete");
		}

		if (e.getSource() == bnStart) {
			if (!panelModel.getSelectedModel().isInstalled())
				GUIsOwnLog.warn("Not starting encoding as the selected model is not installed.");

			GUIsOwnLog.warn("TO DO Start the encoding");
			PromptsToNetAdapter netAdapter = panelModel
					.getSelectedModel()
					.instantiate(Views.permute(display.giveProcessedSubImage(),0,1), logForNetworks);
			//TODO: if this netAdapter has already encoded, we don't do it again
			display.switchToThisNet(netAdapter);
			GUIsOwnLog.warn("TO DO End the encoding");
			//TODO: encoding should be a property of a model
			encodingDone = true;
		}

		if (e.getSource() == chkROIManager) {
			display.enableAddingToRoiManager(chkROIManager.isSelected());
		}

		updateInterface();
	}

	public void updateInterface() {
		bnRect.setEnabled(encodingDone);
		bnPoints.setEnabled(encodingDone);
		bnBrush.setEnabled(encodingDone);
		bnMask.setEnabled(encodingDone);

		//TODO: this was checking if ROIManager is not empty...
		bnComplete.setEnabled(false);
		bnRoi2Mask.setEnabled(false);
	}

	public class LocalDropTarget extends DropTarget {

		@Override
		public void drop(DropTargetDropEvent e) {
			e.acceptDrop(DnDConstants.ACTION_COPY);
			e.getTransferable().getTransferDataFlavors();
			Transferable transferable = e.getTransferable();
			DataFlavor[] flavors = transferable.getTransferDataFlavors();
			for (DataFlavor flavor : flavors) {
				if (flavor.isFlavorJavaFileListType()) {
					try {
						List<File> files = (List<File>) transferable.getTransferData(flavor);
						for (File file : files) {
							mask = getImageMask(file);
							if (mask != null) {
								return;
							}
						}
					}
					catch (UnsupportedFlavorException ex) {
						ex.printStackTrace();
					}
					catch (IOException ex) {
						ex.printStackTrace();
					}
				}
			}
			e.dropComplete(true);
			super.drop(e);
		}
	}

	private ImagePlus getImageMask(File file) {
		GUIsOwnLog.info("Taking mask from file "+file.getAbsolutePath());
		//TODO: outsource this to display, this dialog must have no IJ-specific dependencies
		ImagePlus tmp = IJ.openImage(file.getAbsolutePath());
		if (tmp == null)
			return null;
		/*
		if (imp.getWidth() != tmp.getWidth())
			return null;
		if (imp.getHeight() != tmp.getHeight())
			return null;
			*/
		ImageProcessor ip = tmp.getProcessor();
		mask = new ImagePlus(file.getName(), ip);
		mask.show();
		GUIsOwnLog.info(mask.toString());
		return mask;
	}
	
	private ArrayList<String> getListImages() {
		int[] ids = WindowManager.getIDList();
		ArrayList<String> list = new ArrayList<String>();
		if (ids != null) {
			for (int id : ids) {
				ImagePlus idp = WindowManager.getImage(id);
				if (idp != null) {
					list.add((String)idp.getTitle());
				}
			}
		}
		return list;
	}


	@Override
	public void windowOpened(WindowEvent windowEvent) {}
	@Override
	public void windowClosing(WindowEvent windowEvent) {
		//NB: reacts to closing the window using OS tools (such as "cross decoration icon")
		display.notifyNetToClose();
	}
	@Override
	public void windowClosed(WindowEvent windowEvent) {}
	@Override
	public void windowIconified(WindowEvent windowEvent) {}
	@Override
	public void windowDeiconified(WindowEvent windowEvent) {}
	@Override
	public void windowActivated(WindowEvent windowEvent) {}
	@Override
	public void windowDeactivated(WindowEvent windowEvent) {}
}
