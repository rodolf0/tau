#include <string.h>
#include <stdlib.h>
#include "deht.h"


static DEHT * _new_DEHT(const char *prefix, const char * data_filename,
		const char * key_filename,
		hashKeyIntoTableFunctionPtr hashfun,
        hashKeyforEfficientComparisonFunctionPtr validfun)
{
	DEHT * deht = NULL;

	/* allocate instance */
	deht = (DEHT*)malloc(sizeof(DEHT));
	if (deht == NULL) {
		perror("failed allocating DEHT");
		return NULL;
	}

	/* init members */
	deht->dataFP = NULL;
	deht->keyFP = NULL;

	strncpy(deht->sPrefixFileName, prefix, sizeof(deht->sPrefixFileName));
	deht->hashFunc = hashfun;
	deht->comparisonHashFunc = validfun;
	deht->hashTableOfPointersImageInMemory = NULL;
	deht->hashPointersForLastBlockImageInMemory = NULL;
	deht->anLastBlockSize = NULL;

	/* open the files */
	deht->keyFP = fopen(key_filename, "w+");
	if (deht->keyFP == NULL) {
		perror("fopen of key file");
		goto error_cleanup1;
	}

	deht->dataFP = fopen(data_filename, "w+");
	if (deht->dataFP == NULL) {
		perror("fopen of data file");
		goto error_cleanup2;
	}

	/* success */
	return deht;

error_cleanup2:
	fclose(deht->keyFP);
error_cleanup1:
	free(deht);
	return NULL;
}

static void _init_deht_files(const char * prefix, char * key_filename, char * data_filename)
{
	strcpy(key_filename, prefix);
	strcat(key_filename, ".key");

	strcpy(data_filename, prefix);
	strcat(data_filename, ".data");
}

DEHT *create_empty_DEHT(const char *prefix,
		hashKeyIntoTableFunctionPtr hashfun,
        hashKeyforEfficientComparisonFunctionPtr validfun,
        int numEntriesInHashTable, int nPairsPerBlock, int nBytesPerKey,
        const char *HashName)
{
	char key_filename[200];
	char data_filename[200];
	FILE * f = NULL;
	DEHT * deht = NULL;
	DEHT_DISK_PTR * empty_head_table = NULL;
	size_t entries_written = 0;

	_init_deht_files(prefix, key_filename, data_filename);

	/* make sure the two files do not already exist */
	if ((f = fopen(key_filename, "r")) != NULL) {
		fclose(f);
		fprintf(stderr, "key file already exists\n");
		return NULL;
	}
	if ((f = fopen(data_filename, "r")) != NULL) {
		fclose(f);
		fprintf(stderr, "data file already exists\n");
		return NULL;
	}

	deht = _new_DEHT(prefix, data_filename, key_filename, hashfun, validfun);
	if (deht == NULL) {
		return NULL;
	}

	/* init header */
	deht->header.numEntriesInHashTable = numEntriesInHashTable;
	deht->header.nPairsPerBlock = nPairsPerBlock;
	deht->header.nBytesPerValidationKey = nBytesPerKey;
	strncpy(deht->header.sHashName, HashName, sizeof(deht->header.sHashName));

	/* write header and empty head table */
	if (fwrite(&(deht->header), sizeof(deht->header), 1, deht->keyFP) != 1) {
		perror("could not write header to keys file");
		goto cleanup;
	}
	DEHT_DISK_PTR * empty = (DEHT_DISK_PTR*)malloc(sizeof(DEHT_DISK_PTR))
		);
	if (empty_head_table == NULL) {
		fprintf(stderr, "could not allocate empty head table with %d entries", 
			numEntriesInHashTable);
		goto cleanup;
	}
	size_t entries_written = fwrite(empty_head_table, sizeof(DEHT_DISK_PTR), numEntriesInHashTable, new_deht->keyFP);
	if (entries_written != numEntriesInHashTable) {
		perror(prefix);
		goto create_empty_DEHT_cleanup;
	}
	free(empty_head_table);

	/* Initialize data file */
	/* This initialization invalidates NULL as a data file offset */
	//if ('\0' != putc('\0', new_deht->dataFP)) {
	//	perror(prefix);
	//	goto create_empty_DEHT_cleanup;
	//}

	/* Flush the files */
	//fflush(new_deht->keyFP);
	//fflush(new_deht->dataFP);


	return deht;
}

DEHT *load_DEHT_from_files(const char *prefix,
        hashKeyIntoTableFunctionPtr hashfun,
        hashKeyforEfficientComparisonFunctionPtr validfun)
{
	char key_filename[200];
	char data_filename[200];
	FILE * f = NULL;
	DEHT * deht = NULL;

	_init_deht_files(prefix, key_filename, data_filename);

	/* make sure the two files do exist */
	f = fopen(key_filename, "r");
	if (f == NULL) {
		perror("key file does not exist\n");
		return NULL;
	}
	fclose(f);
	f = fopen(data_filename, "r");
	if (f == NULL) {
		perror("data file does not exist\n");
		return NULL;
	}
	fclose(f);

	deht = _new_DEHT(prefix, data_filename, key_filename, hashfun, validfun);
	if (deht == NULL) {
		return NULL;
	}

	/* read header from file */
	if (fread(&deht->header, sizeof(deht->header), 1, deht->keyFP) != 1) {
		fprintf(stderr, "failed to read header from key file\n");
		close_DEHT_files(deht);
		return NULL;
	}

	return deht;
}


void close_DEHT_files(DEHT *ht)
{
	if (ht == NULL) {
		return;
	}
	write_DEHT_pointers_table(ht);

	if (ht->hashTableOfPointersImageInMemory != NULL) {
		free(ht->hashTableOfPointersImageInMemory);
		ht->hashTableOfPointersImageInMemory = NULL;
	}
	if (ht->hashPointersForLastBlockImageInMemory != NULL) {
		free(ht->hashPointersForLastBlockImageInMemory);
		ht->hashPointersForLastBlockImageInMemory = NULL;
	}
	if (ht->anLastBlockSize != NULL) {
		free(ht->anLastBlockSize);
		ht->anLastBlockSize = NULL;
	}

	/* no point in checking return value of fclose -- this function is void
	 * anyway and is not meant to report errors back */
	fclose(ht->keyFP);
	fclose(ht->dataFP);

	/* since we malloc()'ed ht, it's time for us to free() it */
	free(ht);
}


int add_DEHT(DEHT *ht, const unsigned char *key, int keyLength,
        const unsigned char *data, int dataLength)
{
	return DEHT_STATUS_FAIL;
}

int query_DEHT(DEHT *ht, const unsigned char *key, int keyLength,
        unsigned char *data, int dataMaxAllowedLength)
{


	return DEHT_STATUS_FAIL;
}

int insert_uniquely_DEHT(DEHT *ht, const unsigned char *key, int keyLength,
        const unsigned char *data, int dataLength)
{
	/* the size is not important, just a few bytes -- we don't use the data itself */
	unsigned char tmp[30];
	int succ = query_DEHT(ht, key, keyLength, tmp, sizeof(tmp));

	if (succ == DEHT_STATUS_NOT_NEEDED) {
		/* already exists */
		return DEHT_STATUS_NOT_NEEDED;
	}
	if (succ != DEHT_STATUS_SUCCESS) {
		/* query failed */
		return DEHT_STATUS_FAIL;
	}

	/* if key was not found, add it */
	return add_DEHT(ht, key, keyLength, data, dataLength);
}

int read_DEHT_pointers_table(DEHT *ht)
{
	size_t readcount = 0;

	if (ht->hashTableOfPointersImageInMemory != NULL) {
		return DEHT_STATUS_NOT_NEEDED;
	}

	ht->hashTableOfPointersImageInMemory = (DEHT_DISK_PTR*) malloc(
		sizeof(DEHT_DISK_PTR) * ht->header.numEntriesInHashTable);
	if (ht->hashTableOfPointersImageInMemory == NULL) {
		fprintf(stderr, "allocating memory for DEHT pointers table failed\n");
		return DEHT_STATUS_FAIL;
	}
	if (fseek(ht->keyFP, sizeof(ht->header), SEEK_SET) != 0) {
		perror("fseek failed");
		goto cleanup;
	}

	readcount = fread(ht->hashTableOfPointersImageInMemory, sizeof(DEHT_DISK_PTR), 
		ht->header.numEntriesInHashTable, ht->keyFP);
	if (readcount != ht->header.numEntriesInHashTable) {
		perror("failed to read DEHT pointers table");
		goto cleanup;
	}

	return DEHT_STATUS_SUCCESS;

cleanup:
	free(ht->hashTableOfPointersImageInMemory);
	ht->hashTableOfPointersImageInMemory = NULL;
	return DEHT_STATUS_FAIL;
}

int write_DEHT_pointers_table(DEHT *ht)
{
	size_t written = 0;

	if (ht->hashTableOfPointersImageInMemory == NULL) {
		return DEHT_STATUS_NOT_NEEDED;
	}

	if (fseek(ht->keyFP, sizeof(ht->header), SEEK_SET) != 0) {
		perror("fseek failed");
		return DEHT_STATUS_FAIL;
	}

	written = fwrite(ht->hashTableOfPointersImageInMemory, sizeof(DEHT_DISK_PTR), 
		ht->header.numEntriesInHashTable, ht->keyFP);
	if (written != ht->header.numEntriesInHashTable) {
		perror("failed to write everything");
		return DEHT_STATUS_FAIL;
	}
	fflush(ht->keyFP);

	free(ht->hashTableOfPointersImageInMemory);
	ht->hashTableOfPointersImageInMemory = NULL;
	return DEHT_STATUS_SUCCESS;
}
















