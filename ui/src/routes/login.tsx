import { createFileRoute } from "@tanstack/react-router";
import { AuthCard } from "@/components/application/auth/auth-card";
import { GoogleSignInButton } from "@/modules/auth/GoogleSignInButton";

export const Route = createFileRoute("/login")({
	component: RouteComponent,
});

function RouteComponent() {
	return (
		<main className="grid min-h-screen place-items-center p-6">
			<AuthCard title="Sign in to Payra" description={null}>
				<GoogleSignInButton />
			</AuthCard>
		</main>
	);
}
