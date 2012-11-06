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

						final int sampleRate = 11025;
						final int sampleSize = 2; // in bytes
						final int sampleChannelCfg = AudioFormat.CHANNEL_OUT_MONO;
						final int sampleEncoding = (sampleSize == 1) ? AudioFormat.ENCODING_PCM_8BIT :
															(sampleSize == 2) ? AudioFormat.ENCODING_PCM_16BIT :
															AudioFormat.ENCODING_INVALID;

						final int minBufferSize = AudioTrack.getMinBufferSize(sampleRate, sampleChannelCfg, sampleEncoding);
						final int secondBufferSize = sampleRate * sampleSize;
						final int bufferSize = Math.max(minBufferSize, secondBufferSize);

						final AudioTrack noise = new AudioTrack(AudioManager.STREAM_RING, sampleRate, sampleChannelCfg, sampleEncoding, bufferSize, AudioTrack.MODE_STREAM);
						final long startTime = System.currentTimeMillis();
						final int lagCompensationFactor = 4;
						final int framesPerMessage = 10;
						final double targetFps = (double)sampleRate / frameWidth;

						final TextView message = (TextView)findViewById(R.id.message);

						camera.setPreviewCallback(new PreviewCallback() {

							// make these mutable longs cleaner at some point
							// for now 0:frames 1:elapsed_time

							final long[] counters = new long[16];
							final short[] pcmBuffer = new short[frameWidth];

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

								for(int i = 0; i < pcmBuffer.length; i++) {
									pcmBuffer[i] = (short)(((int)data[frameOffset + i] & 0x00ff) + 128); // convert unsigned 8-bit to signed 16-bit
									pcmBuffer[i] = (short)(255 - pcmBuffer[i]); // invert levels
									pcmBuffer[i] <<= 8; // scale amplitude by 256, or a left-shift of 1 byte
								}

								for(int i = 0; i < lagCompensationFactor; i++)
									noise.write(pcmBuffer, 0, frameWidth);

								noise.play();

								counters[0]++;
								counters[1] = System.currentTimeMillis() - startTime;

								double secs = (double)counters[1] / 1000.0;
								double fps = (double)counters[0] / secs;

								if(counters[0] % framesPerMessage == 1) {
									message.setText(String.format("PaperTracker - #%d %.1fs %dspf %dkB %.1f:%.1ffps (X%.1f)",
												counters[0], secs, frameWidth, bufferSize >> 10, targetFps, fps, targetFps / fps));
								}
							}

						});
						camera.startPreview();
						previewing = true;
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}
		}});

	buttonStopCameraPreview.setOnClickListener(new Button.OnClickListener() {

		@Override
		public void onClick(View v) {

			if(camera !=null && previewing) {
				camera.stopPreview();
				camera.release();
				camera = null;

				previewing = false;
			}
		}});

    }

 @Override
 public void surfaceChanged(SurfaceHolder holder, int format, int width,
   int height) {
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
/*class Extract implements Camera.PreviewCallback {

}*/
		
