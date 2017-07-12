/*
 * MIT License
 *
 * Copyright (c) 2017 朱辉
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.yeetor.minitouch;

import com.android.ddmlib.IDevice;
import com.android.ddmlib.IShellOutputReceiver;
import com.yeetor.adb.AdbDevice;
import com.yeetor.adb.AdbForward;
import com.yeetor.adb.AdbServer;
import com.yeetor.minicap.MinicapInstallException;
import com.yeetor.util.Constant;
import com.yeetor.util.Util;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by harry on 2017/4/19.
 */
public class Minitouch {

    private static final String MINITOUCH_BIN_DIR = "resources" + File.separator + "minicap-bin";
    private static final String REMOTE_PATH = "/data/local/tmp";
    private static final String MINITOUCH_BIN = "minitouch";

    private List<MinitouchListener> listenerList = new ArrayList<MinitouchListener>();

    private AdbDevice device;
    private Thread minitouchThread, minitouchInitialThread;
    private Socket minitouchSocket;
    private OutputStream minitouchOutputStream;
    private AdbForward forward;

    public static void installMinitouch(AdbDevice device) throws MinitouchInstallException {
        if (device == null) {
            throw new MinitouchInstallException("device can't be null");
        }

        String sdk = device.getProperty(Constant.PROP_SDK);
        String abi = device.getProperty(Constant.PROP_ABI);

        if (StringUtils.isEmpty(sdk) || StringUtils.isEmpty(abi)) {
            throw new MinitouchInstallException("cant not get device info. please check device is connected");
        }

        sdk = sdk.trim();
        abi = abi.trim();

        File minitouch_bin = Constant.getMinitouchBin(abi);
        if (minitouch_bin == null || !minitouch_bin.exists()) {
            throw new MinitouchInstallException("File: " + minitouch_bin.getAbsolutePath() + " not exists!");
        }
        try {
            AdbServer.server().executePushFile(device.getIDevice(), minitouch_bin.getAbsolutePath(), REMOTE_PATH + "/" + MINITOUCH_BIN);
        } catch (Exception e) {
            throw new MinitouchInstallException(e.getMessage());
        }

        AdbServer.executeShellCommand(device.getIDevice(), "chmod 777 " + REMOTE_PATH + "/" + MINITOUCH_BIN);
    }

    public Minitouch(AdbDevice device) {
        this.device = device;

        try {
            installMinitouch(device);
        } catch (MinitouchInstallException e) {
            e.printStackTrace();
        }
    }

    public Minitouch(String serialNumber) {
        this(AdbServer.server().getDevice(serialNumber));
    }

    public Minitouch() {
        this(AdbServer.server().getFirstDevice());
    }

    public void addEventListener(MinitouchListener listener) {
        if (listener != null) {
            this.listenerList.add(listener);
        }
    }

    public AdbForward createForward() {
        forward = generateForwardInfo();
        try {
            device.getIDevice().createForward(forward.getPort(), forward.getLocalabstract(), IDevice.DeviceUnixSocketNamespace.ABSTRACT);
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("create forward failed");
        }
        return forward;
    }

    private void removeForward(AdbForward forward) {
        if (forward == null || !forward.isForward()) {
            return;
        }
        try {
            device.getIDevice().removeForward(forward.getPort(), forward.getLocalabstract(), IDevice.DeviceUnixSocketNamespace.ABSTRACT);
        } catch (Exception e) {
        }
    }

    public void start() {
        AdbForward forward = createForward();
        String command = "/data/local/tmp/minitouch" + " -n " + forward.getLocalabstract();
        minitouchThread = startMinitouchThread(command);
        minitouchInitialThread = startInitialThread("127.0.0.1", forward.getPort());
    }

    public void kill() {
        onClose();
        if (minitouchThread != null) {
            minitouchThread.stop();
        }
        // 关闭socket
        if (minitouchSocket != null && minitouchSocket.isConnected()) {
            try {
                minitouchSocket.close();
            } catch (IOException e) {
            }
            minitouchSocket = null;
        }
    }

    public void sendEvent(String str) {
        if (minitouchOutputStream == null) {
            return;
        }
        try {
            minitouchOutputStream.write(str.getBytes());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void sendKeyEvent(int k) {
        AdbServer.executeShellCommand(device.getIDevice(), "input keyevent " + k);
    }

    public void inputText(String str) {
        AdbServer.executeShellCommand(device.getIDevice(), "input text " + str);
    }

    /**
     * 生成forward信息
     */
    private AdbForward generateForwardInfo() {
        AdbForward[] forwards = AdbServer.server().getForwardList();
        // serial_touch_number
        int maxNumber = 0;
        if (forwards.length > 0) {
            for (AdbForward forward : forwards) {
                if (forward.getSerialNumber().equals(device.getIDevice().getSerialNumber())) {
                    String l = forward.getLocalabstract();
                    String[] s = l.split("_");
                    if (s.length == 3) {
                        int n = Integer.parseInt(s[2]);
                        if (n > maxNumber) maxNumber = n;
                    }
                }
            }
        }
        maxNumber += 1;

        String forwardStr = String.format("%s_touch_%d", device.getIDevice().getSerialNumber(), maxNumber);
        int freePort = Util.getFreePort();
        AdbForward forward = new AdbForward(device.getIDevice().getSerialNumber(), freePort, forwardStr);
        return forward;
    }

    private Thread startMinitouchThread(final String command) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    device.getIDevice().executeShellCommand(command, new IShellOutputReceiver() {
                        @Override
                        public void addOutput(byte[] bytes, int offset, int len) {
                            System.out.println(new String(bytes, offset, len));
                        }
                        @Override
                        public void flush() {}
                        @Override
                        public boolean isCancelled() {
                            return false;
                        }
                    }, 0);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

        thread.start();
        return thread;
    }

    private Thread startInitialThread(final String host, final int port) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                int tryTime = 200;
                while (true) {
                    Socket socket = null;
                    byte[] bytes = new byte[256];
                    try {
                        socket = new Socket(host, port);
                        InputStream inputStream = socket.getInputStream();
                        OutputStream outputStream = socket.getOutputStream();
                        int n = inputStream.read(bytes);

                        if (n == -1) {
                            Thread.sleep(10);
                            socket.close();
                        } else {
                            minitouchSocket = socket;
                            minitouchOutputStream = outputStream;
                            onStartup(true);
                            break;
                        }
                    } catch (Exception ex) {
                        if (socket != null) {
                            try {
                                socket.close();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                        continue;
                    }
                    tryTime--;
                    if (tryTime == 0) {
                        onStartup(false);
                        break;
                    }
                }
            }
        });
        thread.start();
        return thread;
    }

    private void onStartup(boolean success) {
        for (MinitouchListener listener : listenerList) {
            listener.onStartup(this, success);
        }
    }

    private void onClose() {
        for (MinitouchListener listener : listenerList) {
            listener.onClose(this);
        }
        removeForward(forward);
    }
}
