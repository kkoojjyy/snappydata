# INSERT

An INSERT statement creates a row or rows and stores them in the named table. The number of values assigned in an INSERT statement must be the same as the number of specified or implied columns.

##Syntax

``` pre
INSERT INTO table-name
    [ ( simple-column-name [ , simple-column-name ]* ) ]
   Query
```

<a id="reference_2A553C72CF7346D890FC904D8654E062__section_69794C56F9E840C991CE0B3A699D6013"></a>

##Description

Query can be:

-   a VALUES list
-   a multiple-row VALUES expression

!!! Note
	SnappyData does not support an INSERT with a subselect query if any subselect query requires aggregation. </p>
Single-row and multiple-row lists can include the keyword DEFAULT. Specifying DEFAULT for a column inserts the column's default value into the column. Another way to insert the default value into the column is to omit the column from the column list and only insert values into other columns in the table. More information is provided in <mark>[VALUES ref-valuesexpression.md). To be confirmed </mark>

[SELECT](select.md) and <mark> To be confirmed[JOIN Operations ref-join.md) </mark> provide additional information.

<a id="reference_2A553C72CF7346D890FC904D8654E062__section_33D88974B03A45EC91226B0D46C14549"></a>


##Example

``` pre
INSERT INTO TRADE.CUSTOMERS
      VALUES (1, 'J Pearson', '07-06-2002', 'VMWare', 1);

-- Insert a new customer into the CUSTOMERS  table,
-- but do not assign  value to  'SINCE'  column
INSERT INTO TRADE.CUSTOMERS(CID ,CUST_NAME , ADDR ,TID)
 VALUES (1, 'J. Pearson', 'VMWare', 1);

-- Insert two new customers using one statement 
-- into the CUSTOMER table as in the previous example, 
-- but do not assign  value to 'SINCE'  field of the new customer.
INSERT INTO TRADE.CUSTOMERS (CID ,CUST_NAME , ADDR ,TID)
 VALUES (1, 'J. Pearson' , 'VMWare', 1),
 (2, 'David Y.' , 'VMWare', 1);

-- Insert the DEFAULT value for the LOCATION column
INSERT INTO TRADE.CUSTOMERS
      VALUES (1, 'J. Pearson', DEFAULT, 'VMWare',1);

-- Insert using a select statement.
INSERT INTO TRADE.NEWCUSTOMERS
     SELECT * from TRADE.CUSTOMERS WHERE TID=1;
```

