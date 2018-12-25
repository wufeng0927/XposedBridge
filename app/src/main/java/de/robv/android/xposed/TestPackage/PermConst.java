package de.robv.android.xposed.TestPackage;

import java.util.ArrayList;
import java.util.List;

/**
 * Permission Constants
 */
public class PermConst {

    public final static List<String> PERMISSIONS_TO_GRANT;
    public final static List<String> PACKAGE_TO_GRANT;
    
    static {
    	PACKAGE_TO_GRANT = new ArrayList<>();
    	
    	//https://github.com/permissions-dispatcher/PermissionsDispatcher
    	PACKAGE_TO_GRANT.add("permissions.dispatcher.sample");//Permission demo
    	PACKAGE_TO_GRANT.add("com.wuba.zhuanzhuan");//58转转
    	
    	PACKAGE_TO_GRANT.add("com.facebook.katana");//Facebook
    	PACKAGE_TO_GRANT.add("com.twitter.android");//Twitter
    	PACKAGE_TO_GRANT.add("jp.naver.line.android");//Line
    	PACKAGE_TO_GRANT.add("com.whatsapp");//WhatsApp
    	
    }

    

    //https://developer.android.com/guide/topics/security/permissions?hl=zh-cn#normal-dangerous
    static {
        PERMISSIONS_TO_GRANT = new ArrayList<>();
        
        //日历
        PERMISSIONS_TO_GRANT.add("android.permission.READ_CALENDAR");
        PERMISSIONS_TO_GRANT.add("android.permission.WRITE_CALENDAR");
        
        
        //拍照
        PERMISSIONS_TO_GRANT.add("android.permission.CAMERA");
        
        //联系人
        PERMISSIONS_TO_GRANT.add("android.permission.READ_CONTACTS");
        PERMISSIONS_TO_GRANT.add("android.permission.WRITE_CONTACTS");
        PERMISSIONS_TO_GRANT.add("android.permission.GET_ACCOUNTS");

        //位置
        PERMISSIONS_TO_GRANT.add("android.permission.ACCESS_FINE_LOCATION");
        PERMISSIONS_TO_GRANT.add("android.permission.ACCESS_COARSE_LOCATION");
        
        //音频
        PERMISSIONS_TO_GRANT.add("android.permission.RECORD_AUDIO");
        
        //手机相关
        PERMISSIONS_TO_GRANT.add("android.permission.READ_PHONE_STATE");
        PERMISSIONS_TO_GRANT.add("android.permission.CALL_PHONE");
        PERMISSIONS_TO_GRANT.add("android.permission.READ_CALL_LOG");
        PERMISSIONS_TO_GRANT.add("android.permission.WRITE_CALL_LOG");
        PERMISSIONS_TO_GRANT.add("android.permission.ADD_VOICEMAIL");
        PERMISSIONS_TO_GRANT.add("android.permission.USE_SIP");
        PERMISSIONS_TO_GRANT.add("android.permission.PROCESS_OUTGOING_CALLS");
        
        //传感器
        PERMISSIONS_TO_GRANT.add("android.permission.BODY_SENSORS");
        
        //短信
        PERMISSIONS_TO_GRANT.add("android.permission.SEND_SMS");
        PERMISSIONS_TO_GRANT.add("android.permission.RECEIVE_SMS");
        PERMISSIONS_TO_GRANT.add("android.permission.READ_SMS");
        PERMISSIONS_TO_GRANT.add("android.permission.RECEIVE_WAP_PUSH");
        PERMISSIONS_TO_GRANT.add("android.permission.RECEIVE_MMS");
        
        //存储
        PERMISSIONS_TO_GRANT.add("android.permission.READ_EXTERNAL_STORAGE");
        PERMISSIONS_TO_GRANT.add("android.permission.WRITE_EXTERNAL_STORAGE");

    }

}
