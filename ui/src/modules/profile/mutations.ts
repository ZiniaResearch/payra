import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "@tanstack/react-router";

import {
	clearBrowserSession,
	loadBrowserKey,
	loadBrowserSession,
} from "@/modules/auth/browser-key-store";
import { createProfileDeletionProof } from "@/modules/auth/browser-proof";
import { AUTH_LOCK } from "@/modules/auth/refresh";
import { notifySessionEnded } from "@/modules/auth/session-events";
import { api } from "@/shared/api/client";
import {
	authenticatedRequest,
	authenticatedRequestUnderLock,
} from "@/shared/api/request";
import { type CurrentAccount, currentAccountQueryOptions } from "./queries";

const writeHeaders = { "X-Requested-With": "XmlHttpRequest" };

export function useUpdateProfileName() {
	return useProfileChange(
		(name: string) => patchProfile({ name }),
		(profile) => ({ name: profile.name }),
	);
}

export function useUpdateProfileCover() {
	return useProfileChange(
		(coverId: string) => patchProfile({ cover_id: coverId }),
		(profile) => ({ cover_id: profile.cover_id, cover_url: profile.cover_url }),
	);
}

export function useUploadAvatar() {
	return useProfileChange(
		(file: File) =>
			authenticatedRequest<CurrentAccount>({
				method: "PUT",
				url: "/api/profile/avatar",
				headers: {
					...writeHeaders,
					"Content-Type": "application/octet-stream",
				},
				data: file,
				timeout: 30_000,
			}),
		(profile) => ({ avatar_url: profile.avatar_url }),
	);
}

function patchProfile(data: { name: string } | { cover_id: string }) {
	return authenticatedRequest<CurrentAccount>({
		method: "PATCH",
		url: "/api/profile",
		headers: writeHeaders,
		data,
	});
}

function useProfileChange<T>(
	mutationFn: (value: T) => Promise<CurrentAccount>,
	changedFields: (profile: CurrentAccount) => Partial<CurrentAccount>,
) {
	const queryClient = useQueryClient();
	const { queryKey } = currentAccountQueryOptions();

	return useMutation({
		networkMode: "always",
		gcTime: 0,
		mutationFn,
		onSuccess: async (profile) => {
			const current = queryClient.getQueryData(queryKey);
			if (current?.account_id !== profile.account_id) {
				return;
			}

			await queryClient.cancelQueries({ queryKey, exact: true });

			queryClient.setQueryData(queryKey, (cached) =>
				cached?.account_id === profile.account_id
					? { ...cached, ...changedFields(profile) }
					: cached,
			);
		},
	});
}

export class ProfileDeletionError extends Error {}

export function useDeleteProfile() {
	const queryClient = useQueryClient();
	const router = useRouter();

	return useMutation({
		networkMode: "always",
		gcTime: 0,
		mutationFn: (accountId: string) =>
			navigator.locks.request(AUTH_LOCK, async () => {
				const session = await loadBrowserSession();
				const key = session && (await loadBrowserKey(session.keyId));

				if (!session || !key) {
					throw new ProfileDeletionError(
						"Your browser key is unavailable. Sign in again before deleting your account.",
					);
				}

				const challenge = await authenticatedRequestUnderLock<{
					account_id: string;
					nonce: string;
				}>({
					method: "POST",
					url: "/api/profile/deletion",
					headers: writeHeaders,
				});

				if (challenge.account_id !== accountId) {
					throw new ProfileDeletionError(
						"The signed-in account changed. Reload your profile and try again.",
					);
				}

				const path = "/api/profile";
				const targetUrl = new URL(
					api.getUri({ url: path }),
					window.location.origin,
				).href;
				const proof = await createProfileDeletionProof(
					session.session_id,
					key.privateKey,
					targetUrl,
					challenge.nonce,
				);
				const { session_id } = await authenticatedRequestUnderLock<{
					session_id: string;
				}>({
					method: "DELETE",
					url: path,
					headers: writeHeaders,
					data: { nonce: challenge.nonce, proof },
				});

				try {
					await clearBrowserSession(session_id);
				} catch {
					// The server already ended all sessions and cleared both cookies.
					console.error("Could not clear the deleted account's browser key.");
				}

				notifySessionEnded(session_id);
				await queryClient.cancelQueries();
				queryClient.clear();
				router.clearCache();
				await router.navigate({ to: "/login", replace: true });
				router.clearCache();
			}),
	});
}
