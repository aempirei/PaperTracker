package org.buttes.shitpreview;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.lang.Math;
import java.lang.Thread;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.graphics.PixelFormat;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.hardware.Camera.*;
import android.os.Bundle;
import android.view.WindowManager;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.util.Log;
import android.media.AudioTrack;
import android.media.AudioTrack.*;
import android.media.AudioFormat;
import android.media.AudioFormat.*;
import android.media.AudioManager;

class AudioPlayer {

	AudioTrack audio;

	final int sampleRate = 11025;
	final int sampleSize = 2; // in bytes
	final int sampleChannelCfg = AudioFormat.CHANNEL_OUT_MONO;
	final int sampleEncoding = (sampleSize == 1) ? AudioFormat.ENCODING_PCM_8BIT :
								(sampleSize == 2) ? AudioFormat.ENCODING_PCM_16BIT :
								AudioFormat.ENCODING_INVALID;

	final int bufferSize = AudioTrack.getMinBufferSize(sampleRate, sampleChannelCfg, sampleEncoding) * 2;
	final int sampleBufferN = sampleRate / 25;
	final short[] sampleBuffer = new short[sampleBufferN];

	final int voicesN = 8;
	final double[] voices = new double[voicesN];

	public AudioPlayer() {
      audio = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, sampleChannelCfg, sampleEncoding, bufferSize, AudioTrack.MODE_STREAM);
		audio.play();
	}

	final double baseFrequency = 110.0;

	public double getNoteHz(double note) {
		return baseFrequency * Math.pow(2.0, note / 12.0);
	}

	private double getNoteStep(double note) {
		return getNoteHz(note) / (double)sampleRate;
	}

	private void stepVoice(int voice, double note) {
		voices[voice] += getNoteStep(note);
		voices[voice] -= Math.floor(voices[voice]);
	}

	private double wave(double x) {
		return (x < 0.5) ? 1.0 : -1.0; 
	}

	private short getSample(int voice, double volume) {
		return (short)(Short.MAX_VALUE * volume * wave(voices[voice]));
	}

	public void setSampleBuffer(int voice, double note, double volume) {
		for(int i = 0; i < sampleBuffer.length; i++) {
			sampleBuffer[i] = getSample(voice, volume);
			stepVoice(voice, note);
		}
	}

	public void addSampleBuffer(int voice, double note, double volume) {
		for(int i = 0; i < sampleBuffer.length; i++) {
			sampleBuffer[i] += getSample(voice, volume);
			stepVoice(voice, note);
		}
	}

	public void write() {
		audio.write(sampleBuffer, 0, sampleBuffer.length);
	}
}

class ScanLine {

	double[] front;
	double[] back;
	double[] scratch;

	final int N;

	public ScanLine(int myN) {
		N = myN;
		front = new double[N];
		back = new double[N];
		scratch = new double[N];
	}

	public void setFromNV21(byte[] data, int offset) {
		for(int i = 0; i < N; i++)
			front[i] = (double)(255 - (0xff & data[offset + i]));
	}

	private void flip() {
		double[] temp = front;
		front = back;
		back = temp;
	}

	public void blur(int r) {
		for(int i = r; i < N - r; i++) {
			back[r] = 0;
			for(int j = -r; j <= r; j++)
				back[i] += front[i + j];
		}
		for(int i = 0; i < r; i++)
			back[i] = back[r];
		for(int i = 0; i < r; i++)
			back[N - 1 - i] = back[N - 1 - r];
		flip();
	}

	double rms;
	double rmsd;
	double rmsd2;
	double rmsd2N;

	double std;
	double var;
	double varN;

	double mean;
	double mean2;

	double sum;
	double sum2;

	double mass;
	double population;
	double centroid;

	public void discriminate() {
		for(int i = 0; i < N; i++)
			back[i] = (front[i] < mean + 2.0 * std) ? 0.0 : 1.0;
		flip();
	}

	public void pdiscriminate() {
		for(int i = 0; i < N; i++)
			back[i] = (front[i] < rms - rmsd) ? 0 : 1;
		flip();
	}

	private double rampX(double[] xs) {
		double y = 0;
		for(int i = 0; i < xs.length; i++)
			y += i * xs[i];
		return y;
	}

	private double sumX(double[] xs) {
		double y = 0;
		for(int i = 0; i < xs.length; i++)
			y += xs[i];
		return y;
	}

	private void centerX(double[] ys, double[] xs, double mu) {
		for(int i = 0; i < xs.length; i++)
			ys[i] = xs[i] - mu;
	}

	private void squareX(double[] ys, double[] xs) {
		for(int i = 0; i < xs.length; i++)
			ys[i] = xs[i] * xs[i];
	}

	public void updateCenter() {
		population = sumX(front);
		mass = rampX(front);
		centroid = mass / (population + 1);
	}


	public void updateRMS() {
		squareX(scratch, front);
		sum2 = sumX(scratch);
		mean2 = sum2 / N;
		rms = Math.sqrt(sum2 / N);
	}

	public void updateRMSD() {

		updateRMS();

		centerX(back, front, rms);
		squareX(scratch, back);
		rmsd2N = sumX(scratch);
		rmsd2 = rmsd2N / N;
		rmsd = Math.sqrt(rmsd2N / N);
	}


	public void updateMean() {
		sum = sumX(front);
		mean = sum / N;
	}

	public void updateStdDev() {

		updateMean();

		centerX(back, front, mean);
		squareX(scratch, back);
		varN = sumX(scratch);
		var = varN / N;
		std = Math.sqrt(varN / N);
	}
}

public class preview extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback
{
	Camera camera;
	SurfaceView surfaceView;
	SurfaceHolder surfaceHolder;

	TextView textView;
	Button button;

	boolean previewing = false;

	/*
	 * get frame size of a preview image (assuming NV21 format)
	 */

	private int frameSize;

	private int getFrameSize() {
		if(frameSize == 0) {
			Camera.Parameters param = camera.getParameters();
			int imgformat = param.getPreviewFormat();
			int bitsperpixel = ImageFormat.getBitsPerPixel(imgformat);
			Camera.Size camerasize = param.getPreviewSize();
			frameSize = (camerasize.width * camerasize.height * bitsperpixel) / 8;
		}
		return frameSize;
	};

	@Override
	public void onCreate(Bundle savedInstanceState)
	{

		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);

		// find widgets

		textView = (TextView)findViewById(R.id.message);
		button = (Button)findViewById(R.id.startcamerapreview);
		surfaceView = (SurfaceView)findViewById(R.id.surfaceview);

		getWindow().setFormat(PixelFormat.YCbCr_420_SP);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

		surfaceHolder = surfaceView.getHolder();

		surfaceHolder.addCallback(this);
		surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

		// setup start camera button

		button.setOnClickListener(new Button.OnClickListener() {

			// these are all generally shared between the 3 threads

			final Handler handler = new Handler();
			final AudioPlayer player = new AudioPlayer();

			final int callbackBuffersN = 20;

			long startTime;
			long frameN;
			double note;

			double[] notes;
			double[] volumes;

			int scanlineOffset;

			ScanLine scanline;

			Camera.Size previewSize;

			@Override
			public void onClick(View v) {

				if(previewing) {

					if(camera != null) {
						camera.stopPreview();
					}

					previewing = false;

					button.setText("Play");

				} else {

					previewing = true;

					button.setText("Pause");

					//
					// reset the shared data
					//

					note = 0.0;
					frameN = 0;

					startTime = System.currentTimeMillis();

					notes = new double[player.voicesN];
					volumes = new double[player.voicesN];

					//
					//
					// start up the camera
					//
					//

					if(camera == null) {
						camera = Camera.open();
					}

					if(camera != null) {

						try {

							camera.setPreviewDisplay(surfaceHolder);
							camera.setDisplayOrientation(0);

							//
							// fill out camera data -- probably should make a class
							// 

							for(int i = 0; i < callbackBuffersN; i++)
								camera.addCallbackBuffer(new byte[getFrameSize()]);

							previewSize = camera.getParameters().getPreviewSize();

							scanlineOffset = previewSize.width * (previewSize.height >> 1);

							scanline = new ScanLine(previewSize.width);

							camera.setPreviewCallbackWithBuffer(new PreviewCallback() {

								@Override
								public void onPreviewFrame(byte[] data, Camera camera) {

									// setScanline(data);
									// processScanline();

									scanline.setFromNV21(data, scanlineOffset);

									// scanline.blur(1);

									// scanline.updateRMSD();

									scanline.updateStdDev();

									scanline.discriminate();

									scanline.updateCenter();

									note = 36.0 * scanline.centroid / (scanline.N - 1);

									frameN++;

									camera.addCallbackBuffer(data);
								}
							});

							camera.startPreview();

						} catch (IOException e) {

							e.printStackTrace();
						}

						//
						//
						// textview status message thread
						//
						//
						
						new Thread(new Runnable() {
							 @Override
							public void run() {

								Runnable runnableUpdateStatus = new Runnable() {
									@Override	
									public void run() {
										long elapsedTime = System.currentTimeMillis() - startTime;
										double secs = (double)elapsedTime / 1000.0;
										double fps = (double)frameN / secs;
										textView.setText(String.format("PaperTracker - p=%.1f/%.1f/%.1f µ=%.1f σ=%.1f %.1f  %.1fhz  #%d  %.1fs   %.1ffps",
											scanline.population,scanline.mass,(double)scanline.centroid, (double)scanline.mean, (double)scanline.std,
											note, player.getNoteHz(note), frameN, secs, fps));
									}
								};

								while(previewing) {
									try {
										Thread.sleep(200);
										handler.post(runnableUpdateStatus);
									} catch(InterruptedException e) {
										// who cares
									}
								}
							}
						}).start();

						//
						// start up the audio player thread
						//
					
						new Thread(new Runnable() {
							@Override
							public void run() {
	
								double loud = 1.0;
								int voice = 0;
	
								// do the sounds!
	
								while(previewing) {
									player.setSampleBuffer(voice, note, loud);
									player.write();
								}
	
							}
						}).start();
	
						// end of if(camera != null) { ... }
					}

					// end of if(previewing) { ... } 
				}

				// end of onClick()
			} 
		});
	}

	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
	}

	@Override
	public void surfaceCreated(SurfaceHolder holder) {
	}

	@Override
	public void surfaceDestroyed(SurfaceHolder holder) {
	}

	@Override
	public void onPreviewFrame(byte[] data, Camera camera) {
	}
}

/* 
class Extract implements Camera.PreviewCallback {
}
*/
