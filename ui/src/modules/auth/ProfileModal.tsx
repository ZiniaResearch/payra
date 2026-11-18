import type { ComponentProps } from "react";
import { Modal, ModalHeader } from "@/components/base/modal/modal";

export function ProfileModal(
	props: Pick<ComponentProps<typeof Modal>, "isOpen" | "onOpenChange">,
) {
	return (
		<Modal {...props} size="lg" className="min-h-80">
			<ModalHeader title="Profile" closeLabel="Close profile" />
		</Modal>
	);
}
