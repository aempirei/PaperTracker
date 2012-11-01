package org.buttes.shitpreview;
import java.io.IOException;
import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.graphics.PixelFormat;
import android.hardware.Camera;
import android.hardware.Camera.*;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.util.Log;

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

	getWindow().setFormat(PixelFormat.UNKNOWN);
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
						camera.setPreviewCallback(new PreviewCallback() {
							@Override
							public void onPreviewFrame(byte[] data, Camera camera) {
								        Log.i("butt", "the first byte of this shit frame is" + data[0]);
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
	Log.i("butt", "the first byte of this shit frame is" + data[0]);
	}


}
/*class Extract implements Camera.PreviewCallback {

}*/
		
