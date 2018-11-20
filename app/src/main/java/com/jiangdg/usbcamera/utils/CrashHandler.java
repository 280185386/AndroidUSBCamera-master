package com.jiangdg.usbcamera.utils;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Build;
import android.os.Environment;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.Thread.UncaughtExceptionHandler;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

public class CrashHandler implements UncaughtExceptionHandler {
	// System default Uncaught Exception handling class
	private UncaughtExceptionHandler mDefaultHandler;
	// CrashHandler
	private static CrashHandler instance = new CrashHandler();

	private Context mContext;
	private Class<?> mainActivityClass;
	private Map<String, String> infos = new HashMap<String, String>();

	private CrashHandler() {
	}

	public static CrashHandler getInstance() {
		return instance;
	}

	public void init(Context context, Class<?> activityClass) {
		mContext = context;
		this.setMainActivityClass(activityClass);
		mDefaultHandler = Thread.getDefaultUncaughtExceptionHandler();
		Thread.setDefaultUncaughtExceptionHandler(this);
	}


	@Override
	public void uncaughtException(Thread thread, Throwable ex) {
		if (!handleException(ex) && mDefaultHandler != null) {
			// If the user does not process, let the system default exception handler to handle
			mDefaultHandler.uncaughtException(thread, ex);
		} else {
			// Create File Error .txt
			//logError(ex);
			Toast.makeText(mContext, "Error : "+ex.toString(), Toast.LENGTH_SHORT).show();
			try {
				Thread.sleep(3000);
			} catch (InterruptedException e) {
//				Log.e("debug", "error：", e);
			}
		}
	}

	private boolean handleException(Throwable ex) {
		if (ex == null) {
			return false;
		}
		new Thread(new Runnable() {
			@Override
			public void run() {
				Looper.prepare();
//				Toast.makeText(mContext.getApplicationContext(),
//						"มีผิดพลาด ลองอีกครั้ง...", Toast.LENGTH_LONG).show();
				Looper.loop();
			}
		}).start();
		// ข้อมูลพารามิเตอร์อุปกรณ์
		collectDeviceInfo(mContext.getApplicationContext());
		// Create File Error
//		logError(ex);
		return true;
	}


	public void collectDeviceInfo(Context ctx) {
		try {
			PackageManager pm = ctx.getPackageManager();
			PackageInfo pi = pm.getPackageInfo(ctx.getPackageName(),
					PackageManager.GET_ACTIVITIES);
			if (pi != null) {
				String versionName = pi.versionName == null ? "null"
						: pi.versionName;
				String versionCode = pi.versionCode + "";
				infos.put("versionName", versionName);
				infos.put("versionCode", versionCode);
			}
		} catch (NameNotFoundException e) {
		}
		Field[] fields = Build.class.getDeclaredFields();
		for (Field field : fields) {
			try {
				field.setAccessible(true);
				infos.put(field.getName(), field.get(null).toString());
			} catch (Exception e) {
			}
		}
	}


	private void logError(Throwable ex) {
		StringBuffer sb = new StringBuffer();
		int num = ex.getStackTrace().length;
		for (int i=0;i<num;i++){
			sb.append(ex.getStackTrace()[i].toString());
			sb.append("\n");
		}

		File file = new File(Environment.getExternalStorageDirectory().getAbsolutePath()
				+File.separator+System.currentTimeMillis()+".txt");
		FileOutputStream fos = null;
		try {
			fos = new FileOutputStream(file);
			fos.write((sb.toString()+"异常："+ex.getLocalizedMessage()).getBytes());
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}finally {
			try {
				fos.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	public Class<?> getMainActivityClass() {
		return mainActivityClass;
	}

	public void setMainActivityClass(Class<?> mainActivityClass) {
		this.mainActivityClass = mainActivityClass;
	}
}
