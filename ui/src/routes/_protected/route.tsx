import { createFileRoute, Outlet, redirect } from "@tanstack/react-router";
import { Toaster } from "@/components/base/toast/toast";
import { DirectionProvider } from "@/components/foundations/direction/direction";
import { loadBrowserSession } from "@/modules/auth/browser-key-store";
import { Sidebar } from "@/modules/layout/Sidebar";

export const Route = createFileRoute("/_protected")({
	ssr: false,
	beforeLoad: async () => {
		if (import.meta.env.SSR) return { sessionId: undefined };

		const session = await loadBrowserSession().catch(() => undefined);

		if (!session) {
			throw redirect({ to: "/login", replace: true });
		}

		return { sessionId: session.session_id };
	},
	component: ProtectedLayout,
});

function ProtectedLayout() {
	return (
		<DirectionProvider locale="en">
			<div className="flex min-h-svh gap-3 bg-background-full p-3 text-text-primary">
				<Sidebar />
				<div className="flex min-w-0 flex-1 flex-col">
					<main className="min-w-0 flex-1">
						<Outlet />
					</main>
				</div>
			</div>
			<Toaster position="bottom-end" />
		</DirectionProvider>
	);
}
