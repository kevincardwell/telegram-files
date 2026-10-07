import { useTelegramMethod } from "@/hooks/use-telegram-method";
import useSWRMutation from "swr/mutation";
import { request } from "@/lib/api";
import { useToast } from "@/hooks/use-toast";
import { useSWRConfig } from "swr";
import React, {
  type FormEvent,
  useCallback,
  useEffect,
  useRef,
  useState,
} from "react";
import { useDebounce } from "use-debounce";
import { Ellipsis, LoaderCircle } from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import {
  TelegramConstructor,
  type TelegramObject,
  WebSocketMessageType,
} from "@/lib/websocket-types";
import { Label } from "@/components/ui/label";
import { Input } from "@/components/ui/input";
import {
  InputOTP,
  InputOTPGroup,
  InputOTPSlot,
} from "@/components/ui/input-otp";
import { useWebSocketMessage } from "@/lib/ws-store";
import { useTelegramAccount } from "@/hooks/use-telegram-account";
import TGDuck16Plane from "@/components/animations/tg-duck16_plane.json";
import TGQRPlane from "@/components/animations/tg-qr-plane.json";
import dynamic from "next/dynamic";
import QRCodeStyling, { type Options } from "qr-code-styling";

interface AccountCreatorProps {
  isAdd?: boolean;
  proxyName: string | undefined;
  onCreated?: (id: string) => void;
  onLoginSuccess?: () => void;
}

const Lottie = dynamic(() => import("lottie-react"), { ssr: false });

export default function AccountCreator({
  isAdd,
  proxyName,
  onCreated,
  onLoginSuccess,
}: AccountCreatorProps) {
  const { triggerMethod, isMethodExecuting } = useTelegramMethod();
  const { toast } = useToast();
  const { mutate } = useSWRConfig();
  const { account, resetAccount } = useTelegramAccount();
  const [initSuccessfully, setInitSuccessfully] = useState(false);
  const [authState, setAuthState] = useState<number | undefined>(undefined);
  const [qrCodeLink, setQrCodeLink] = useState<string | undefined>(undefined);
  const [phoneNumber, setPhoneNumber] = useState("");
  const [code, setCode] = useState("");
  const [password, setPassword] = useState("");
  const [isDeMethodExecuting] = useDebounce(isMethodExecuting, 500, {
    leading: true,
  });
  const {
    trigger: triggerCreate,
    isMutating: isCreateMutating,
    error: createError,
  } = useSWRMutation<{ id: string }, Error>(
    "/telegram/create",
    async (key: string) => {
      return await request(key, {
        method: "POST",
        body: JSON.stringify({
          proxyName: proxyName,
        }),
      });
    },
    {
      onSuccess: (data) => {
        onCreated?.(data.id);
      },
    },
  );
  const [debounceIsCreateMutating] = useDebounce(isCreateMutating, 1000, {
    leading: true,
  });

  const handleAuthState = useCallback(
    (state: TelegramObject) => {
      switch (state.constructor) {
        case TelegramConstructor.WAIT_PHONE_NUMBER:
        case TelegramConstructor.WAIT_CODE:
        case TelegramConstructor.WAIT_PASSWORD:
        case TelegramConstructor.WAIT_PREMIUM_PURCHASE:
          setAuthState(state.constructor);
          break;
        case TelegramConstructor.WAIT_OTHER_DEVICE_CONFIRMATION:
          setAuthState(state.constructor);
          setQrCodeLink(state.link as string);
          break;
        case TelegramConstructor.STATE_READY:
          toast({
            variant: "success",
            description: "Account added successfully",
          });
          setTimeout(() => {
            void mutate("/telegrams");
            onLoginSuccess?.();
            setPhoneNumber("");
            setCode("");
            setPassword("");
          }, 1000);
          break;
        default:
          setTimeout(() => {
            void mutate("/telegrams");
          }, 500);
          console.log("Unknown telegram constructor:", state.constructor);
      }
    },
    [mutate, onLoginSuccess, toast],
  );

  useEffect(() => {
    if (account) {
      if (
        !isAdd &&
        account.status === "inactive" &&
        account.lastAuthorizationState
      ) {
        setInitSuccessfully(true);
        handleAuthState(account.lastAuthorizationState);
        return;
      }
    }

    if (isAdd && !initSuccessfully) {
      resetAccount();
    }
  }, [account, handleAuthState, initSuccessfully, isAdd, resetAccount]);

  useWebSocketMessage((message) => {
    if (message.type === WebSocketMessageType.AUTHORIZATION) {
      handleAuthState(message.data as TelegramObject);
    }
  });

  useEffect(() => {
    if (phoneNumber) {
      setPhoneNumber((prev) => prev.replaceAll(/\D/g, ""));
    }
  }, [phoneNumber]);

  if (debounceIsCreateMutating) {
    return (
      <div className="flex items-center justify-center space-x-2 text-xl">
        <span>Initializing account, please wait</span>
        <Ellipsis className="h-4 w-4 animate-pulse" />
      </div>
    );
  }

  if (createError) {
    return (
      <div className="text-center text-xl">
        <span className="mr-3 text-3xl">😲</span>
        Initializing account failed, please try again later.
      </div>
    );
  }

  if (!initSuccessfully) {
    return (
      <div className="flex flex-col items-center justify-center space-y-4">
        <Lottie
          className="mb-10 h-28 w-28 md:mb-3"
          animationData={TGDuck16Plane}
          loop={true}
        />
        <Button
          className={cn("w-full", debounceIsCreateMutating ? "opacity-50" : "")}
          disabled={debounceIsCreateMutating}
          onClick={async () => {
            await triggerCreate().then(() => {
              void mutate("/telegrams");
              setInitSuccessfully(true);
            });
          }}
        >
          Start Initialization
        </Button>
      </div>
    );
  }

  if (!authState && !isMethodExecuting) {
    return (
      <div className="flex flex-col items-center justify-center space-y-2 rounded bg-gray-50 p-2">
        <p>Waiting for the telegram account to be initialized, please wait.</p>
        <p>If it takes too long, please refresh the page or try again later.</p>
        <Ellipsis className="h-4 w-4 animate-pulse" />
      </div>
    );
  }

  const authStateFormFields = {
    [TelegramConstructor.WAIT_PHONE_NUMBER]: (
      <div className="space-y-2">
        <Label htmlFor="phone">Phone Number</Label>
        <p className="text-xs text-gray-500">
          Should with country code like: 8613712345678
        </p>
        <Input
          id="phone"
          value={phoneNumber}
          onChange={(e) => setPhoneNumber(e.target.value)}
          disabled={isMethodExecuting}
          required
        />
      </div>
    ),
    [TelegramConstructor.WAIT_OTHER_DEVICE_CONFIRMATION]: (
      <QRCode link={qrCodeLink} />
    ),
    [TelegramConstructor.WAIT_CODE]: (
      <div className="space-y-2">
        <Label htmlFor="code">Authentication Code</Label>
        <p className="text-xs text-gray-500">
          Please enter the code sent to your telegram account.
        </p>
        <InputOTP
          id="code"
          maxLength={6}
          value={code}
          disabled={isMethodExecuting}
          required
          onChange={(value) => setCode(value)}
        >
          <InputOTPGroup>
            <InputOTPSlot index={0} />
            <InputOTPSlot index={1} />
            <InputOTPSlot index={2} />
            <InputOTPSlot index={3} />
            <InputOTPSlot index={4} />
            <InputOTPSlot index={5} />
          </InputOTPGroup>
        </InputOTP>
      </div>
    ),
    [TelegramConstructor.WAIT_PASSWORD]: (
      <div className="space-y-2">
        <Label htmlFor="password">Password</Label>
        <p className="text-xs text-gray-500">
          You have enabled two-step verification, please enter your password.
        </p>
        <Input
          id="password"
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          disabled={isMethodExecuting}
          required
        />
        <p className="rounded border border-amber-200 bg-amber-50 p-2 text-xs text-amber-700 dark:border-amber-800 dark:bg-amber-950 dark:text-amber-400">
          Using passkeys instead of a password? Close this dialog, start over,
          and use the <strong>LOG IN BY QR CODE</strong> option to authenticate
          from an existing Telegram device.
        </p>
      </div>
    ),
    [TelegramConstructor.WAIT_PREMIUM_PURCHASE]: (
      <div className="space-y-2 rounded border border-red-200 bg-red-50 p-3 dark:border-red-800 dark:bg-red-950">
        <p className="font-medium text-red-700 dark:text-red-400">
          Telegram Premium Required
        </p>
        <p className="text-xs text-red-600 dark:text-red-500">
          This account requires an active Telegram Premium subscription to log
          in. Please purchase Telegram Premium in the official Telegram app and
          try again.
        </p>
      </div>
    ),
  };

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (authState === TelegramConstructor.WAIT_PHONE_NUMBER) {
      await triggerMethod({
        data: {
          phoneNumber: phoneNumber,
          settings: null,
        },
        method: "SetAuthenticationPhoneNumber",
      });
    } else if (authState === TelegramConstructor.WAIT_CODE) {
      await triggerMethod({
        data: {
          code: code,
        },
        method: "CheckAuthenticationCode",
      });
    } else if (authState === TelegramConstructor.WAIT_PASSWORD) {
      await triggerMethod({
        data: {
          password: password,
        },
        method: "CheckAuthenticationPassword",
      });
    }
  };

  const handleRequestQrCodeAuthentication = async () => {
    await triggerMethod({
      data: {
        otherUserIds: null,
      },
      method: "RequestQrCodeAuthentication",
    });
  };

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      {authState && (
        <>
          {authStateFormFields[authState]}
          {authState !== TelegramConstructor.WAIT_OTHER_DEVICE_CONFIRMATION &&
            authState !== TelegramConstructor.WAIT_PREMIUM_PURCHASE && (
            <Button
              type="submit"
              className={cn("w-full", isMethodExecuting ? "opacity-50" : "")}
              disabled={isMethodExecuting}
            >
              {isDeMethodExecuting ? (
                <LoaderCircle className="h-4 w-4 animate-spin" />
              ) : (
                "🚀 Submit"
              )}
            </Button>
          )}
          {authState === TelegramConstructor.WAIT_PHONE_NUMBER && (
            <Button
              variant="outline"
              className={cn("w-full", isMethodExecuting ? "opacity-50" : "")}
              disabled={isMethodExecuting}
              onClick={handleRequestQrCodeAuthentication}
            >
              {isDeMethodExecuting ? (
                <LoaderCircle className="h-4 w-4 animate-spin" />
              ) : (
                "LOG IN BY QR CODE"
              )}
            </Button>
          )}
        </>
      )}
    </form>
  );
}

const options: Options = {
  width: 280,
  height: 280,
  type: "svg",
  image: "blank.png",
  margin: 10,
  qrOptions: {
    errorCorrectionLevel: "M",
  },
  cornersSquareOptions: {
    type: "extra-rounded",
  },
  imageOptions: {
    imageSize: 0.4,
    margin: 8,
  },
  dotsOptions: {
    type: "rounded",
  },
};

function QRCode({ link }: { link?: string }) {
  const [qrCode, setQrCode] = useState<QRCodeStyling>();
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (ref.current) {
      qrCode?.append(ref.current);
    }
  }, [qrCode, ref]);

  useEffect(() => {
    if (link) {
      if (!qrCode) {
        const qrCode = new QRCodeStyling({
          ...options,
          data: link,
        });
        setQrCode(qrCode);
      } else {
        qrCode.update({
          data: link,
        });
      }
    }
  }, [link, qrCode]);

  if (!link) {
    return (
      <div className="flex items-center justify-center">
        <LoaderCircle className="h-14 w-14 animate-spin" />
      </div>
    );
  }

  return (
    <div className="flex flex-col items-center justify-center space-y-2">
      <div className="relative flex items-center justify-center">
        <div className="overflow-hidden rounded-3xl bg-white" ref={ref} />
        <Lottie
          className="absolute left-1/2 top-1/2 z-10 h-14 w-14 -translate-x-1/2 -translate-y-1/2 transform rounded-full bg-gray-800"
          animationData={TGQRPlane}
          loop={true}
        />
      </div>
      <div className="rounded-lg bg-white bg-opacity-80 p-1 dark:bg-gray-800">
        <p className="text-center text-xs text-gray-500 dark:text-gray-400">
          Scan the QR code with your telegram app to log in.
        </p>
      </div>
    </div>
  );
}
