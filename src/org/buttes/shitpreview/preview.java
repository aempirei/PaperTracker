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

			// hmm... stats on the data gets shared

			double scanlineMean;
			double scanlineDev;
			double scanlineCenter;

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
					//
					// start up the audio player
					//
					//

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

							for(int i = 0; i < callbackBuffersN; i++) {
								camera.addCallbackBuffer(new byte[getFrameSize()]);
							}

							camera.setPreviewCallbackWithBuffer(new PreviewCallback() {

								final Size previewSize = camera.getParameters().getPreviewSize();

								final int scanlineOffset = previewSize.width * (previewSize.height >> 1);
								final int scanlineN = previewSize.width;

								int[] scanline = new int[scanlineN];

								private void setScanline(byte[] data) {
									scanlineMean = 0;
									for(int i = 0; i < scanline.length; i++) {
										scanline[i] = (int)(0xff & data[scanlineOffset + i]);
										scanlineMean += (double)scanline[i];
									}
									scanlineMean /= (double)scanline.length;
									scanlineDev = 0;
									for(int i = 0; i < scanline.length; i++) {
										scanlineDev += Math.pow((double)scanline[i] - scanlineMean, 2.0);
									}
									scanlineDev = Math.sqrt(scanlineDev / (double)scanline.length);
								}

								private void processScanline() {
									int scanlinePop = 1;
									int scanlineMass = 0;
									for(int i = 0; i < scanline.length; i++) {
										int k = (scanline[i] < scanlineMean - 2.0 * scanlineDev) ? 1 : 0;
										scanlinePop += k;
										scanlineMass += k * i;
									}
									scanlineCenter = (double)scanlineMass / (double)scanlinePop;
								}

								@Override
								public void onPreviewFrame(byte[] data, Camera camera) {

									setScanline(data);
									processScanline();

									note = 36.0 * scanlineCenter / (double)(scanlineN - 1.0);

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
										textView.setText(String.format("PaperTracker - %.1f  µ=%.1f σ=%.1f  %.1f  %.1fhz  #%d  %.1fs   %.1ffps",
										scanlineCenter, scanlineMean, scanlineDev, note, player.getNoteHz(note), frameN, secs, fps));
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
