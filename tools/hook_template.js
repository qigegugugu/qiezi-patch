/*
 * Frida 通用模板（Android）
 * 用法: frida -U -f <包名> -l hook_template.js --no-pause
 * 先改 CONFIG 里的包名和目标类，再逐段启用。
 */

var CONFIG = {
  packageName: 'com.example.app',   // 目标包名
  pkgPrefix: 'com.example',         // 只列这个包下的类，避免刷屏
  targetClass: 'com.example.app.net.Api',
  targetMethod: 'checkSign'
};

/* ---------- 1. 摸清结构：列出包内所有已加载的类 ---------- */
function listClasses() {
  Java.perform(function () {
    Java.enumerateLoadedClasses({
      onMatch: function (name) {
        if (name.indexOf(CONFIG.pkgPrefix) === 0) console.log('[class]', name);
      },
      onComplete: function () { console.log('--- 枚举结束 ---'); }
    });
  });
}

/* ---------- 2. 看某个类的全部方法签名 ---------- */
function dumpClass(className) {
  Java.perform(function () {
    var C = Java.use(className);
    C.class.getDeclaredMethods().forEach(function (m) {
      console.log(m.toString());
    });
  });
}

/* ---------- 3. 打印某个方法的入参与返回值（最常用） ---------- */
function traceMethod(className, methodName) {
  Java.perform(function () {
    var C = Java.use(className);
    C[methodName].overloads.forEach(function (overload) {
      overload.implementation = function () {
        var args = Array.prototype.slice.call(arguments);
        console.log('>>> ' + methodName + ' args=' + JSON.stringify(args));
        var ret = overload.apply(this, arguments);
        console.log('<<< ' + methodName + ' ret=' + ret);
        return ret;
      };
    });
  });
}

/* ---------- 4. 强制返回固定值 ---------- */
function forceReturn(className, methodName, value) {
  Java.perform(function () {
    var C = Java.use(className);
    C[methodName].overloads.forEach(function (overload) {
      overload.implementation = function () { return value; };
    });
  });
}

/* ---------- 5. SSL Pinning 绕过（覆盖常见实现） ---------- */
function bypassSslPinning() {
  Java.perform(function () {
    try {
      var X509TrustManager = Java.use('javax.net.ssl.X509TrustManager');
      var SSLContext = Java.use('javax.net.ssl.SSLContext');
      var TrustManager = Java.registerClass({
        name: 'dev.frida.TrustAll',
        implements: [X509TrustManager],
        methods: {
          checkClientTrusted: function () {},
          checkServerTrusted: function () {},
          getAcceptedIssuers: function () { return []; }
        }
      });
      var TrustManagers = [TrustManager.$new()];
      var ctx = SSLContext.getInstance('TLS');
      ctx.init(null, TrustManagers, null);
      SSLContext.getInstance.overload('java.lang.String').implementation = function (p) {
        return ctx;
      };
      console.log('[+] TrustManager 已替换');
    } catch (e) { console.log('[-] TrustManager 替换失败: ' + e); }

    try {
      var Pinner = Java.use('okhttp3.CertificatePinner');
      Pinner.check.overloads.forEach(function (o) {
        o.implementation = function () { console.log('[+] 跳过 CertificatePinner.check'); };
      });
    } catch (e) { console.log('[-] 未使用 okhttp CertificatePinner'); }

    try {
      var HostnameVerifier = Java.use('javax.net.ssl.HostnameVerifier');
      Java.use('javax.net.ssl.HttpsURLConnection').setDefaultHostnameVerifier(
        Java.registerClass({
          name: 'dev.frida.AnyHost',
          implements: [HostnameVerifier],
          methods: { verify: function () { return true; } }
        }).$new()
      );
      console.log('[+] HostnameVerifier 已放宽');
    } catch (e) { console.log('[-] HostnameVerifier 处理失败: ' + e); }
  });
}

/* ---------- 6. 反调试：干掉常见检测 ---------- */
function disableAntiDebug() {
  Java.perform(function () {
    try {
      var Debug = Java.use('android.os.Debug');
      Debug.isDebuggerConnected.implementation = function () { return false; };
      console.log('[+] isDebuggerConnected -> false');
    } catch (e) {}
    try {
      var Tracer = Java.use('android.os.Trace');
      Tracer.beginSection.implementation = function () {};
    } catch (e) {}
  });
}

/* 按需启用下面几行 */
Java.perform(function () {
  console.log('[*] 目标: ' + CONFIG.packageName);
  bypassSslPinning();       // 抓包前先开这个
  // traceMethod(CONFIG.targetClass, CONFIG.targetMethod);
  // forceReturn(CONFIG.targetClass, CONFIG.targetMethod, true);
  // listClasses();
});
