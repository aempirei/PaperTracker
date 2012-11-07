package org.buttes.shitpreview;
import java.io.IOException;
import java.util.List;
import java.lang.Math;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
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
public class preview extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback
{
	Camera camera;
	SurfaceView surfaceView;
	SurfaceHolder surfaceHolder;
	boolean previewing = false;

	private int getFrameSize() {
		Camera.Parameters param = camera.getParameters();
		int imgformat = param.getPreviewFormat();
		int bitsperpixel = ImageFormat.getBitsPerPixel(imgformat);
		Camera.Size camerasize = param.getPreviewSize();
		return (camerasize.width * camerasize.height * bitsperpixel) / 8;
	};

	@Override
	public void onCreate(Bundle savedInstanceState)
	{

	super.onCreate(savedInstanceState);
	setContentView(R.layout.main);

	Button buttonStartCameraPreview = (Button)findViewById(R.id.startcamerapreview);
	Button buttonStopCameraPreview = (Button)findViewById(R.id.stopcamerapreview);

	getWindow().setFormat(PixelFormat.YCbCr_420_SP);
	getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

	surfaceView = (SurfaceView)findViewById(R.id.surfaceview);
	surfaceHolder = surfaceView.getHolder();
	surfaceHolder.addCallback(this);
	surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

	// possibly updated by callbacks

	final TextView message = (TextView)findViewById(R.id.message);

	// configure audio ahead-of-time

	final int sampleRate = 8000;
	final int sampleSize = 2; // in bytes
	final int sampleChannelCfg = AudioFormat.CHANNEL_OUT_MONO;
	final int sampleEncoding = (sampleSize == 1) ? AudioFormat.ENCODING_PCM_8BIT :
										(sampleSize == 2) ? AudioFormat.ENCODING_PCM_16BIT :
										AudioFormat.ENCODING_INVALID;

	final int minBufferSize = AudioTrack.getMinBufferSize(sampleRate, sampleChannelCfg, sampleEncoding);
	final int secondBufferSize = sampleRate * sampleSize;
	final int bufferSize = Math.max(minBufferSize, secondBufferSize);

	final AudioTrack noise = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, sampleChannelCfg, sampleEncoding, bufferSize, AudioTrack.MODE_STREAM);

	// setup start camera button

	buttonStartCameraPreview.setOnClickListener(new Button.OnClickListener() {
		@Override
		public void onClick(View v) {

			if(!previewing) {

				camera = Camera.open();
				
				if (camera != null) {

					try {

						camera.setPreviewDisplay(surfaceHolder);
						camera.setDisplayOrientation(0);
	
						final Size previewSize = camera.getParameters().getPreviewSize();
					
						final int frameWidth = previewSize.width;
						final int frameOffset = previewSize.height / 2;

						final long startTime = System.currentTimeMillis();
						final int framesPerMessage = 10;
						final double targetFps = (double)sampleRate / frameWidth;

						final int waveWidth = sampleRate / 5;
						final short tone[][] = new short[frameWidth][frameWidth];

						for(int i = 0;i<frameWidth;i++) {
							for(int j = 0;j<frameWidth;j++) {
								tone[i][j] = (short)Math.rint(16384.0 * Math.sin(j * Math.PI * 55.0 * Math.pow(2.0, (double)i / 12.0)));
							}
						}

						camera.setPreviewCallback(new PreviewCallback() {

							// make these mutable longs cleaner at some point
							// for now 0:frames 1:elapsed_time

							final long[] counters = new long[16];
							final short[] pcmBuffer = new short[frameWidth];
							final byte[] conv = new byte[frameWidth];

							@Override
							public void onPreviewFrame(byte[] data, Camera camera) {

							/*
							 * TODO:
							 * 1. perform 8-bit Y-channel to 16-bit mono PCM conversion
							 * 2. invert, normalize & stretch pcm
							 * 3. centroid position and width detection
							 * 4. select frequency scale or proceedural instrument table
							 * a. probably not a not fourier basis like DCT-II/II transform pairs,
							 * b. try equal temperament chromatic scale: base_hz * (2 ** (1/12) ** note_n)
							 * 5. centroid position, width to frequency, amplitude conversion
							 * 6. freq to time domain composition and compress range
							 * 7. lag compensation
							 */

							 double mean;
							 double std;
							 double sum;
							 long w;
							 long v;
							 w=0;
							 sum=0.0;

							 for(int i = 0; i < frameWidth; i++) {
								 double v = i * (255-(((int)data[frameOffset + i] & 0x00ff) + 128));

								 /*
									 pcmBuffer[i] = (short)(((int)data[frameOffset + i] & 0x00ff) + 128); // convert unsigned 8-bit to signed 16-bit
									 pcmBuffer[i] = (short)(255 - pcmBuffer[i]); // invert levels
									 pcmBuffer[i] <<= 8; // scale amplitude by 256, or a left-shift of 1 byte
								  */

								  sum += v;
								}

								mean = sum / frameWidth;
								
								int arg = (int)Math.rint(mean);

								noise.write(tone[arg], 0, frameWidth);
								//noise.write(pcmBuffer, 0, frameWidth);
								//noise.write(pcmBuffer, 0, frameWidth);

								counters[0]++;
								counters[1] = System.currentTimeMillis() - startTime;

								double secs = (double)counters[1] / 1000.0;
								double fps = (double)counters[0] / secs;
								double runRate = targetFps / fps;

								if(counters[0] % framesPerMessage == 1) {
									message.setText(String.format("PaperTracker - #%d %.1fs %dspf %dkB %.1f : %.1f fps X %.1f %.1f hz",
												counters[0], secs, frameWidth, bufferSize >> 10, targetFps, fps, runRate, fps * frameWidth));
								}
							}

						});

						camera.startPreview();

						noise.play();

						previewing = true;

					} catch (IOException e) {

						e.printStackTrace();
					}
				}
			}
		}});

	// setup stop camera button

	buttonStopCameraPreview.setOnClickListener(new Button.OnClickListener() {

		@Override
		public void onClick(View v) {

			if(camera != null && previewing) {

				camera.stopPreview();
				camera.release();

				noise.stop();

				camera = null;

				previewing = false;
			}
		}});

	}

	@Override
		public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
			// TODO Auto-generated method stub

		}

	@Override
		public void surfaceCreated(SurfaceHolder holder) {
			// TODO Auto-generated method stub

		}

	@Override
		public void surfaceDestroyed(SurfaceHolder holder) {
			// TODO Auto-generated method stub

		}

	@Override
		public void onPreviewFrame(byte[] data, Camera camera) {
		}
}

/* 
class Extract implements Camera.PreviewCallback {
}
*/
