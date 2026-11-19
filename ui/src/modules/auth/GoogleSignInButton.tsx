import {
	type CodeResponse,
	GoogleOAuthProvider,
	useGoogleLogin,
	useGoogleOAuth,
} from "@react-oauth/google";
import { useQueryClient } from "@tanstack/react-query";
import { useRouter } from "@tanstack/react-router";
import { isAxiosError } from "axios";
import { useState } from "react";
import { SocialButton } from "@/components/base/social-button/social-button";
import { signInWithGoogle } from "./api";
import { generateBrowserKey } from "./browser-key";
import { saveBrowserKey, saveBrowserSession } from "./browser-key-store";
import { AUTH_LOCK } from "./refresh";

export function GoogleSignInButton() {
	const clientId = import.meta.env.VITE_GOOGLE_CLIENT_ID;

	if (!clientId) {
		return <output>Google sign-in is unavailable right now.</output>;
	}

	return (
		<GoogleOAuthProvider clientId={clientId}>
			<GoogleCodeButton />
		</GoogleOAuthProvider>
	);
}

function GoogleCodeButton() {
	const router = useRouter();
	const queryClient = useQueryClient();
	const [message, setMessage] = useState("");
	const [pending, setPending] = useState(false);
	const { scriptLoadedSuccessfully } = useGoogleOAuth();

	async function handleGoogleSuccess({
		code,
	}: Pick<CodeResponse, "code">): Promise<void> {
		setMessage("Signing in...");

		try {
			await navigator.locks.request(AUTH_LOCK, async () => {
				const { keyPair, publicKey } = await generateBrowserKey();
				const keyId = await saveBrowserKey(keyPair);
				const session = await signInWithGoogle(code, publicKey);

				await saveBrowserSession({
					session_id: session.session_id,
					refresh_token_hash: session.refresh_token_hash,
					keyId,
				});
				// A new login must not inherit the previous account's cached data.
				await queryClient.cancelQueries();
				queryClient.clear();
				router.clearCache();
			});

			// Page loading may itself need the auth lock, so navigate after
			// releasing it rather than waiting on a protected loader while locked.
			await router.navigate({ to: "/messages/new", replace: true });
		} catch (error) {
			if (isAxiosError(error) && error.response) {
				setMessage(
					`Payra server responded with HTTP ${error.response.status}.`,
				);
			} else {
				setMessage("Could not complete the sign-in request.");
			}
		} finally {
			setPending(false);
		}
	}

	function handleGoogleError() {
		setPending(false);
		setMessage("Google sign-in was cancelled or failed. Please try again.");
	}

	const login = useGoogleLogin({
		flow: "auth-code",
		ux_mode: "popup",
		scope: "openid email profile",
		overrideScope: true,
		onSuccess: handleGoogleSuccess,
		onError: handleGoogleError,
		onNonOAuthError: handleGoogleError,
	});

	function handleClick() {
		setMessage("Opening Google...");
		setPending(true);
		login();
	}

	return (
		<div className="flex w-full flex-col items-center gap-2 text-center">
			<SocialButton
				brand="google"
				appearance="white"
				fullWidth
				disabled={pending || !scriptLoadedSuccessfully}
				onClick={handleClick}
			>
				Continue with Google
			</SocialButton>

			{message && <output>{message}</output>}
		</div>
	);
}
