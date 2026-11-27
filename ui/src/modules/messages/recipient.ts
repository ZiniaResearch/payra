export const RECIPIENTS_PATH = "/api/sms/recipients";

declare const recipientIdBrand: unique symbol;

/** A saved contact's ID, as returned by the recipient API. */
export type RecipientId = string & { readonly [recipientIdBrand]: true };

export interface SavedRecipient {
	id: RecipientId;
	phone: string;
	name: string | null;
}

export function compactRecipientPhone(phone: string) {
	return phone.replace(/[\s().-]/g, "");
}

export function normalizeRecipientPhone(phone: string) {
	const national = /^(?:0|\+880)(1[3-9][0-9]{8})$/.exec(
		compactRecipientPhone(phone),
	)?.[1];
	return national ? `+880${national}` : undefined;
}

export function localRecipientPhone(phone: string) {
	return `0${phone.slice(4)}`;
}

function isRecipientId(value: unknown): value is RecipientId {
	return (
		typeof value === "string" &&
		/^[\da-f]{8}(?:-[\da-f]{4}){3}-[\da-f]{12}$/i.test(value)
	);
}

export function parseSavedRecipient(value: unknown): SavedRecipient {
	if (
		typeof value !== "object" ||
		value === null ||
		!("id" in value) ||
		!isRecipientId(value.id) ||
		!("phone" in value) ||
		typeof value.phone !== "string" ||
		normalizeRecipientPhone(value.phone) !== value.phone ||
		!("name" in value) ||
		(value.name !== null && typeof value.name !== "string")
	) {
		throw new Error("Invalid saved recipient response.");
	}

	return { id: value.id, phone: value.phone, name: value.name };
}
