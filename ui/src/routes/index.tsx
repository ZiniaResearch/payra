import { createFileRoute } from "@tanstack/react-router";
import { useRedirectIfSignedIn } from "@/modules/auth/use-redirect-if-signed-in";

export const Route = createFileRoute("/")({ component: App });

function App() {
	const canShowPage = useRedirectIfSignedIn();
	if (!canShowPage) return null;

	return <main className="bg-stone-400">Hello</main>;
}
