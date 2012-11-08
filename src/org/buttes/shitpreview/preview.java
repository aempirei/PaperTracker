package org.buttes.shitpreview;
import java.io.IOException;
import java.util.List;
import java.util.TreeSet;
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
	final int sampleBufferN = sampleRate / 45;
	final short[] sampleBuffer = new short[sampleBufferN];

	final int voicesN = 5;
	final double[] voices = new double[voicesN];

	public AudioPlayer() {
      audio = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, sampleChannelCfg, sampleEncoding, bufferSize, AudioTrack.MODE_STREAM);
		audio.play();
	}

	final double baseFrequency = 55.0;

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

	private void stepVoices(double[] notes) {
		for(int i = 0; i < notes.length; i++)
			stepVoice(i, notes[i]);
	}

	private double wave(double x) {
		return (x < 0.5) ? 1.0 : -1.0; 
	}

	private short getSample(int voice, double volume) {
		return (short)(Short.MAX_VALUE * volume * wave(voices[voice]));
	}

	private short getPolySample(double[] volumes) {
		if(volumes.length == 0)
			return 0;
		double y = 0.0;
		for(int i = 0; i < volumes.length; i++)
			y += volumes[i] * wave(voices[i]);
		return (short)(Short.MAX_VALUE * y / volumes.length);
	}

	public void clearSampleBuffer() {
		for(int i = 0; i < sampleBuffer.length; i++)
			sampleBuffer[i] = 0;
	}

	public void polySampleBuffer(double notes[], double[] volumes) {
		for(int i = 0; i < sampleBuffer.length; i++) {
			sampleBuffer[i] = getPolySample(volumes);
			stepVoices(notes);
		}
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

class Range implements Comparable<Range> {

	public double a;
	public double b;

	public Range(double _a, double _b) {
		a = _a;
		b = _b;
	}
	public double mu() {
		return (a + b) / 2.0;
	}
	public double sigma() {
		return b - a;
	}
	@Override
	public int compareTo(Range o) {
		return (mu() > o.mu()) ? 1 : (mu() < o.mu()) ? -1 : 0;
	}
}


class ScanLine {

	double[] front;
	double[] back;
	double[] scratch;

	final int N;

	public ScanLine(int _N) {
		N = _N;
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
			back[i] = 0;
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

	Range[] ranges;

	public void discriminate() {
		for(int i = 0; i < N; i++)
			back[i] = (front[i] < mean + 1.5 * std) ? 0.0 : 1.0;
		flip();
	}

	public void pdiscriminate() {
		for(int i = 0; i < N; i++)
			back[i] = (front[i] < rms + rmsd) ? 0.0 : 1.0;
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
		for(double x : xs)
			y += x;
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

	public void updateRanges(int maxRanges, double smallestRangeSigma) {

		TreeSet<Range> rangeTreeSet = new TreeSet<Range>();

		for(int i = 0; i < N - 1; i++) {
			if(front[i] > 0.9) {
				for(int j = i + 1; j < N; j++) {
					if(front[j] < 0.1) {
						rangeTreeSet.add(new Range(i,j-1));
						i = j - 1;
						break;
					}
				}
			}
		}

		while(rangeTreeSet.size() > maxRanges)
			rangeTreeSet.remove(rangeTreeSet.first());

		while(rangeTreeSet.size() > 0 && rangeTreeSet.first().sigma() < smallestRangeSigma)
			rangeTreeSet.remove(rangeTreeSet.first());

		ranges = rangeTreeSet.toArray(new Range[0]);
	}
}

public class preview extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback
{
	Camera camera;
	SurfaceView surfaceView;
	SurfaceHolder surfaceHolder;

	TextView textView;
	Button button;

	ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

	boolean previewing = false;

	//
	// get frame size of a preview image (assuming NV21 format)
	//

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
			final double minRangeSigma = 2.0;

			long startTime;
			long frameN;

			final double noteMax = 36.0;

			private double rangeToNote(Range range, double muMax) {
				return noteMax * range.mu() / muMax;
			}

			private double rangeToVolume(Range range) {
				return Math.min(1.0, 0.70 + range.sigma() / 100.0);
			}

			double[] notes;
			double[] volumes;

			int scanlineOffset;

			ScanLine scanline;

			Camera.Size previewSize;

			// testy stuff

			int rangeN;

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

					lock.writeLock().lock();
					try {
						notes = new double[0];
						volumes = new double[0];
						frameN = 0;
					} finally {
						lock.writeLock().unlock();
					}

					startTime = System.currentTimeMillis();

					//
					//
					// start up the camera
					//
					//

					if(camera == null)
						camera = Camera.open();

					if(camera != null) {

						try {

							camera.setPreviewDisplay(surfaceHolder);
							camera.setDisplayOrientation(0);

							for(int i = 0; i < callbackBuffersN; i++)
								camera.addCallbackBuffer(new byte[getFrameSize()]);

							previewSize = camera.getParameters().getPreviewSize();

							scanlineOffset = previewSize.width * (previewSize.height >> 1);

							scanline = new ScanLine(previewSize.width);

							camera.setPreviewCallbackWithBuffer(new PreviewCallback() {

								@Override
								public void onPreviewFrame(byte[] data, Camera camera) {

									// calculations

									scanline.setFromNV21(data, scanlineOffset);

									camera.addCallbackBuffer(data);

									scanline.blur(1);
									scanline.updateStdDev();
									scanline.discriminate();
									scanline.updateRanges(player.voicesN, minRangeSigma);

									// note+volume assignment

									lock.writeLock().lock();

									try {

										rangeN = scanline.ranges.length;
										notes = new double[rangeN];
										volumes = new double[rangeN];

										for(int i = 0; i < rangeN; i++) {
											notes[i] = rangeToNote(scanline.ranges[i], scanline.N);
											volumes[i] = rangeToVolume(scanline.ranges[i]);
										}

										frameN++;

									} finally {
										lock.writeLock().unlock();
									}
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
						
						Thread thStatus = new Thread(new Runnable() {
							 @Override
							public void run() {
								
								Runnable runnableUpdateStatus = new Runnable() {
									@Override	
									public void run() {
										lock.readLock().lock();
										try {
											long elapsedTime = System.currentTimeMillis() - startTime;
											double secs = (double)elapsedTime / 1000.0;
											double fps = (double)frameN / secs;
											textView.setText(String.format("PaperTracker - %d %s µ=%.1f σ=%.1f #%d %.1fs %.1ffps",
												rangeN, rangeN == 1 ? "voice" : "voices", scanline.mean, scanline.std, frameN, secs, fps));
										} finally {
											lock.readLock().unlock();
										}
									}
								};

								while(previewing) {
									try {
										Thread.sleep(125);
										handler.post(runnableUpdateStatus);
									} catch(InterruptedException e) {
										// who cares
									}
								}
							}
						});

						thStatus.setPriority(Thread.MIN_PRIORITY);
						thStatus.start();

						//
						// start up the audio player thread
						//
					
						Thread thAudio = new Thread(new Runnable() {
							@Override
							public void run() {

								double[] _notes;
								double[] _volumes;

								while(previewing) {

									lock.readLock().lock();

									try {
										_notes = notes;
										_volumes = volumes;
									} finally {
										lock.readLock().unlock();
									}
	
									player.polySampleBuffer(_notes, _volumes);

									player.write();
								}
	
							}
						});
						
						thAudio.start();

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
