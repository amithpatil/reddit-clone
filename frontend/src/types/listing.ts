export interface Thing<T> {
  kind: string;
  data: T;
}

export interface ListingData<T> {
  after: string | null;
  before: string | null;
  children: Thing<T>[];
}

export interface Listing<T> {
  kind: string;
  data: ListingData<T>;
}
