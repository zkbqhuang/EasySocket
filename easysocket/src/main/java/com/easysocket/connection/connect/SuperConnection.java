package com.easysocket.connection.connect;

import com.easysocket.callback.SuperCallBack;
import com.easysocket.config.EasySocketOptions;
import com.easysocket.connection.action.SocketAction;
import com.easysocket.connection.action.SocketStatus;
import com.easysocket.connection.dispatcher.ResponseDispatcher;
import com.easysocket.connection.dispatcher.SocketActionDispatcher;
import com.easysocket.connection.heartbeat.HeartManager;
import com.easysocket.connection.iowork.IOManager;
import com.easysocket.connection.reconnect.AbsReconnection;
import com.easysocket.entity.IsNeedReconnect;
import com.easysocket.entity.SocketAddress;
import com.easysocket.entity.basemsg.BaseCallbackSender;
import com.easysocket.entity.basemsg.ISender;
import com.easysocket.entity.exception.NoNullException;
import com.easysocket.interfaces.config.IConnectionSwitchListener;
import com.easysocket.interfaces.conn.IConnectionManager;
import com.easysocket.interfaces.conn.ISocketActionListener;
import com.easysocket.utils.LogUtil;
import com.easysocket.utils.Util;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Author：Alex
 * Date：2019/5/29
 * Note：socket连接的超类
 */
public abstract class SuperConnection implements IConnectionManager {

    /**
     * 连接状态
     */
    protected final AtomicInteger connectionStatus = new AtomicInteger(SocketStatus.SOCKET_DISCONNECTED);
    /**
     * 连接线程
     */
    private Thread connectThread; //连接线程
    /**
     * socket地址信息
     */
    protected SocketAddress socketAddress;
    /**
     * socket行为分发器
     */
    private SocketActionDispatcher actionDispatcher;
    /**
     * 重连管理器
     */
    private AbsReconnection reconnection;
    /**
     * io管理器
     */
    private IOManager ioManager;
    /**
     * 心跳管理器
     */
    private HeartManager heartManager;
    /**
     * 配置信息
     */
    protected EasySocketOptions socketOptions;
    /**
     * socket回调消息的分发器
     */
    private ResponseDispatcher responseDispatcher;
    /**
     * 连接切换的监听
     */
    private IConnectionSwitchListener connectionSwitchListener;

    public SuperConnection(SocketAddress socketAddress) {
        this.socketAddress = socketAddress;
        actionDispatcher = new SocketActionDispatcher(this, socketAddress);
    }

    @Override
    public void subscribeSocketAction(ISocketActionListener iSocketActionListener) {
        actionDispatcher.subscribe(iSocketActionListener);
    }

    @Override
    public void unSubscribeSocketAction(ISocketActionListener iSocketActionListener) {
        actionDispatcher.unsubscribe(iSocketActionListener);
    }

    @Override
    public synchronized IConnectionManager setOptions(EasySocketOptions socketOptions) {
        if (socketOptions == null) return this;

        this.socketOptions = socketOptions;

        if (ioManager != null)
            ioManager.setOptions(socketOptions);

        if (heartManager != null)
            heartManager.setOptions(socketOptions);

        if (responseDispatcher != null)
            responseDispatcher.setSocketOptions(socketOptions);

        //更改了重连器
        if (reconnection != null && !reconnection.equals(socketOptions.getReconnectionManager())) {
            reconnection.detach();
            reconnection = socketOptions.getReconnectionManager();
            reconnection.attach(this);
        }
        return this;
    }

    @Override
    public EasySocketOptions getOptions() {
        return socketOptions;
    }

    @Override
    public synchronized void connect() {
        LogUtil.d("开始socket连接");
        //检查当前连接状态
        if (connectionStatus.get() != SocketStatus.SOCKET_DISCONNECTED) {
            LogUtil.d("socket已经连接");
            return;
        }
        connectionStatus.set(SocketStatus.SOCKET_CONNECTING);
        if (socketAddress.getIp() == null || socketAddress.getPort() <= 0) {
            throw new NoNullException("连接参数有误，请检查是否设置了连接IP和port");
        }
        //初始化心跳管理器
        if (heartManager == null)
            heartManager = new HeartManager(this, actionDispatcher);

        //重连管理器相关
        if (reconnection != null)
            reconnection.detach();
        reconnection = socketOptions.getReconnectionManager();
        if (reconnection != null)
            reconnection.attach(this);

        //开启连接线程
        connectThread = new ConnectThread("connect thread for" + socketAddress);
        connectThread.setDaemon(true);
        connectThread.start();
    }

    @Override
    public synchronized void disconnect(IsNeedReconnect isNeedReconnect) {
        if (connectionStatus.get() == SocketStatus.SOCKET_DISCONNECTING) {
            return;
        }
        connectionStatus.set(SocketStatus.SOCKET_DISCONNECTING);

        //开启断开连接线程
        String info = socketAddress.getIp() + " : " + socketAddress.getPort();
        Thread disconnThread = new DisconnectThread(isNeedReconnect, "disconn thread：" + info);
        disconnThread.setDaemon(true);
        disconnThread.start();
    }

    /**
     * 断开连接线程
     */
    private class DisconnectThread extends Thread {
        IsNeedReconnect isNeedReconnect; //当前断开连接是否需要重连

        public DisconnectThread(IsNeedReconnect isNeedReconnect, String name) {
            super(name);
            this.isNeedReconnect = isNeedReconnect;
        }

        @Override
        public void run() {
            try {
                //关闭io线程
                if (ioManager != null)
                    ioManager.closeIO();
                //关闭回调分发器线程
                if (responseDispatcher != null)
                    responseDispatcher.stopThread();
                //关闭连接线程
                if (connectThread != null && connectThread.isAlive() && !connectThread.isInterrupted()) {
                    connectThread.interrupt();
                }
                LogUtil.d("关闭socket连接");
                //关闭连接
                closeConnection();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                connectionStatus.set(SocketStatus.SOCKET_DISCONNECTED);
                actionDispatcher.dispatchAction(SocketAction.ACTION_DISCONNECTION, isNeedReconnect);
            }
        }
    }

    /**
     * 连接线程
     */
    private class ConnectThread extends Thread {

        public ConnectThread(String name) {
            super(name);
        }

        @Override
        public void run() {
            try {
                openConnection();
            } catch (Exception e) {
                //连接异常
                e.printStackTrace();
                LogUtil.d("socket连接失败");
                connectionStatus.set(SocketStatus.SOCKET_DISCONNECTED);
                actionDispatcher.dispatchAction(SocketAction.ACTION_CONN_FAIL, new IsNeedReconnect(true)); //第二个参数指需要重连

            }
        }
    }

    /**
     * 连接打开成功
     */
    protected void onConnectionOpened() {
        LogUtil.d("socket连接成功");
        //连接成功
        actionDispatcher.dispatchAction(SocketAction.ACTION_CONN_SUCCESS);
        connectionStatus.set(SocketStatus.SOCKET_CONNECTED);
        startManager();
    }

    //开启相关管理器
    private void startManager() {
        responseDispatcher = new ResponseDispatcher(this);
        ioManager = new IOManager(this, actionDispatcher);
        ioManager.startIO();
    }

    //切换了主机的IP和端口
    @Override
    public synchronized void switchHost(SocketAddress socketAddress) {
        if (socketAddress != null) {
            SocketAddress oldAddress = this.socketAddress;
            this.socketAddress = socketAddress;

            if (actionDispatcher != null)
                actionDispatcher.setSocketAddress(socketAddress);

            if (connectionSwitchListener != null) {
                connectionSwitchListener.onSwitchConnectionInfo(this, oldAddress, socketAddress);
            }
        }

    }

    public void setOnConnectionSwitchListener(IConnectionSwitchListener listener) {
        connectionSwitchListener = listener;
    }

    @Override
    public boolean isConnectViable() {
        //即当前socket是否处于没连接的状态，状态的初始值是没连接的，还有当连接失败或主动调用断开连接的时候，都为没连接的状态
        return connectionStatus.get() == SocketStatus.SOCKET_DISCONNECTED;
    }

    @Override
    public int getConnectionStatus() {
        return connectionStatus.get();
    }

    /**
     * 打开连接
     *
     * @throws IOException
     */
    protected abstract void openConnection() throws Exception;

    /**
     * 关闭连接
     *
     * @throws IOException
     */
    protected abstract void closeConnection() throws IOException;

    /**
     * 发送bytes数据
     *
     * @param bytes
     * @return
     */
    private IConnectionManager sendBytes(byte[] bytes) {
        if (ioManager != null)
            ioManager.sendBytes(bytes);
        return this;
    }

    @Override
    public void onCallBack(SuperCallBack callBack) {
        callBack.setSocketOptions(socketOptions);
        responseDispatcher.addSocketCallback(callBack);
    }


    @Override
    public synchronized IConnectionManager upBytes(byte[] bytes) {
        sendBytes(bytes);
        return this;
    }

    @Override
    public synchronized IConnectionManager upString(String sender) {
        sendBytes(sender.getBytes());
        return this;
    }

    @Override
    public synchronized IConnectionManager upObject(ISender sender) {
        sendBytes(sender.parse());
        return this;
    }

    @Override
    public synchronized IConnectionManager upCallbackMessage(BaseCallbackSender sender) {
        //设置一个20位随机字符串作为识别标识
        sender.setSinger(Util.getRandomChar(20));
        sendBytes(sender.parse());
        return this;
    }


    @Override
    public HeartManager getHeartManager() {
        return heartManager;
    }
}
