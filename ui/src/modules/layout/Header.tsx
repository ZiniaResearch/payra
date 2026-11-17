export function Header({ title }: { title: string }) {
	return (
		<header className="flex items-center border-b border-separator-border bg-background-primary-default px-6 py-4">
			<p className="text-headline-medium text-text-primary">{title}</p>
		</header>
	);
}
