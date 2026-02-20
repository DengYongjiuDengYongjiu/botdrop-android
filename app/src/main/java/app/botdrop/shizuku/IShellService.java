package app.botdrop.shizuku;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;

public interface IShellService extends IInterface {
    String DESCRIPTOR = "app.botdrop.shizuku.IShellService";

    String executeCommand(String command, int timeoutMs) throws RemoteException;

    void destroy() throws RemoteException;

    abstract class Stub extends Binder implements IShellService {
        static final int TRANSACTION_executeCommand = IBinder.FIRST_CALL_TRANSACTION + 0;
        static final int TRANSACTION_destroy = IBinder.FIRST_CALL_TRANSACTION + 1;

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static IShellService asInterface(IBinder binder) {
            if (binder == null) {
                return null;
            }
            IInterface iInterface = binder.queryLocalInterface(DESCRIPTOR);
            if (iInterface instanceof IShellService) {
                return (IShellService) iInterface;
            }
            return new Proxy(binder);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            switch (code) {
                case INTERFACE_TRANSACTION:
                    reply.writeString(DESCRIPTOR);
                    return true;
                case TRANSACTION_executeCommand:
                    data.enforceInterface(DESCRIPTOR);
                    String command = data.readString();
                    int timeoutMs = data.readInt();
                    String result = executeCommand(command, timeoutMs);
                    reply.writeNoException();
                    reply.writeString(result);
                    return true;
                case TRANSACTION_destroy:
                    data.enforceInterface(DESCRIPTOR);
                    destroy();
                    reply.writeNoException();
                    return true;
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }

        private static class Proxy implements IShellService {
            private final IBinder mRemote;

            Proxy(IBinder remote) {
                mRemote = remote;
            }

            @Override
            public IBinder asBinder() {
                return mRemote;
            }

            @Override
            public String executeCommand(String command, int timeoutMs) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(command);
                    data.writeInt(timeoutMs);
                    mRemote.transact(TRANSACTION_executeCommand, data, reply, 0);
                    reply.readException();
                    return reply.readString();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public void destroy() throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_destroy, data, reply, 0);
                    reply.readException();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }
        }
    }
}
